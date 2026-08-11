package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "NEXION_MYSQL_IT", matches = "1")
class AppCapacityReservationMySqlIntegrationTest {

    @Test
    void userRowLockSerializesSameAccountWhileReservationSumStaysAccountScoped() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        String users = "nx_e20_user_probe_" + suffix;
        String orders = "nx_e20_order_probe_" + suffix;
        try (Connection setup = connection()) {
            execute(setup, "CREATE TABLE `" + users + "` (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            execute(setup, "CREATE TABLE `" + orders + "` ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, quantity INT NOT NULL, "
                    + "order_status VARCHAR(32) NOT NULL, activation_status VARCHAR(32) NULL, is_deleted TINYINT NOT NULL DEFAULT 0, "
                    + "INDEX idx_user_status(user_id, order_status, activation_status)) ENGINE=InnoDB");
            execute(setup, "INSERT INTO `" + users + "` (id) VALUES (7),(8)");
            execute(setup, "INSERT INTO `" + orders + "` "
                    + "(user_id,quantity,order_status,activation_status,is_deleted) VALUES "
                    + "(7,1,'PENDING_PAYMENT','WAITING_PAYMENT',0),"
                    + "(7,9,'COMPLETED','ACTIVATED',0),"
                    + "(8,4,'PAID','PROVISIONING',0)");

            assertThat(reserved(setup, orders, 7L)).isEqualTo(1);
            assertThat(reserved(setup, orders, 8L)).isEqualTo(4);

            try (Connection first = connection(); Connection otherAccount = connection()) {
                first.setAutoCommit(false);
                lockUser(first, users, 7L);

                otherAccount.setAutoCommit(false);
                lockUser(otherAccount, users, 8L);
                otherAccount.rollback();

                var executor = Executors.newSingleThreadExecutor();
                try {
                    var blocked = executor.submit(() -> {
                        try (Connection second = connection()) {
                            second.setAutoCommit(false);
                            execute(second, "SET SESSION innodb_lock_wait_timeout=1");
                            lockUser(second, users, 7L);
                            return 0;
                        } catch (SQLException ex) {
                            return ex.getErrorCode();
                        }
                    });
                    assertThat(blocked.get(5, TimeUnit.SECONDS)).isEqualTo(1205);
                } finally {
                    executor.shutdownNow();
                }

                first.rollback();
            }

            try (Connection replay = connection()) {
                replay.setAutoCommit(false);
                lockUser(replay, users, 7L);
                replay.rollback();
            }
        } finally {
            try (Connection cleanup = connection(); Statement statement = cleanup.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS `" + orders + "`");
                statement.execute("DROP TABLE IF EXISTS `" + users + "`");
            }
        }
    }

    private int reserved(Connection connection, String table, long userId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(quantity),0) FROM `" + table + "` "
                + "WHERE user_id=? AND is_deleted=0 "
                + "AND UPPER(order_status) IN ('PENDING_PAYMENT','PAID','PROCESSING','PROVISIONING') "
                + "AND UPPER(COALESCE(activation_status,'WAITING_PAYMENT')) NOT IN "
                + "('ACTIVATED','REFUNDED','CANCELLED','PROVISIONING_FAILED')";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void lockUser(Connection connection, String table, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM `" + table + "` WHERE id=? FOR UPDATE")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private Connection connection() throws SQLException {
        String url = value("NEXION_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        return DriverManager.getConnection(url, value("NEXION_MYSQL_USERNAME", "root"),
                required("NEXION_MYSQL_PASSWORD"));
    }

    private String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when NEXION_MYSQL_IT=1");
        }
        return value;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
