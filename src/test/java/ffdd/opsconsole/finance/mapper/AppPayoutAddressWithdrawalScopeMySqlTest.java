package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AppPayoutAddressWithdrawalScopeMySqlTest {
    private static final String PREFIX = "nexion_payout_address_it_";

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(jdbcUrl("127.0.0.1:13306", schema)).contains(":13306/" + schema + "?");
        for (String endpoint : Arrays.asList(null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/other")) {
            assertThatThrownBy(() -> jdbcUrl(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : Arrays.asList(null, "nexion", "mysql", PREFIX + "a", schema + "`")) {
            assertThatThrownBy(() -> jdbcUrl("127.0.0.1:13306", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_PAYOUT_ADDRESS_SCOPE_IT", matches = "true")
    void onlyResolvedWithdrawalsStopBlockingAddressChanges() throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            try (var statement = admin.createStatement(); var port = statement.executeQuery("SELECT @@port")) {
                assertThat(port.next()).isTrue();
                assertThat(port.getInt(1)).isEqualTo(13306);
            }
            execute(admin, "CREATE DATABASE " + schema);
            try (Connection fixture = connect(schema)) {
                try (var statement = fixture.createStatement(); var selected = statement.executeQuery("SELECT DATABASE()")) {
                    assertThat(selected.next()).isTrue();
                    assertThat(selected.getString(1)).isEqualTo(schema);
                }
                execute(fixture, "CREATE TABLE nx_withdrawal_order (id BIGINT PRIMARY KEY,user_id BIGINT,"
                        + "status VARCHAR(32) NULL,is_deleted INT NOT NULL) ENGINE=InnoDB");
                Configuration configuration = new Configuration(new Environment("isolated-payout-address",
                        new JdbcTransactionFactory(), new DriverManagerDataSource(url(schema), "root", password())));
                configuration.addMapper(AppPayoutAddressMapper.class);
                try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                    AppPayoutAddressMapper mapper = session.getMapper(AppPayoutAddressMapper.class);
                    SoftAssertions softly = new SoftAssertions();
                    List<String> resolved = List.of("CONFIRMED", "SUCCESS", "REFUNDED", "COMPLETED",
                            "REJECTED", "CANCELLED", "FAILED", "confirmed", "refunded");
                    for (String status : resolved) {
                        fixture(fixture, status);
                        session.clearCache();
                        softly.assertThat(mapper.unsettledWithdrawalCount(7L)).as("resolved %s", status).isZero();
                    }
                    // Orphaned/failed/rejected intermediate states can still need reconciliation or a refund.
                    for (String status : Arrays.asList("SUBMITTED", "PENDING", "REVIEW_PENDING", "EXTENDED_HOLD",
                            "FROZEN", "REVIEW_PASSED", "PROCESSING", "SENT", "TX_ORPHANED", "DEAD",
                            "REVIEW_REJECTED", "TX_FAILED", "ADDRESS_INVALID", "UNKNOWN", "", null)) {
                        fixture(fixture, status);
                        session.clearCache();
                        softly.assertThat(mapper.unsettledWithdrawalCount(7L)).as("unresolved %s", status).isEqualTo(1);
                    }
                    softly.assertAll();
                }
            } finally {
                execute(admin, "DROP DATABASE " + schema);
            }
        }
    }

    private static void fixture(Connection connection, String status) throws Exception {
        execute(connection, "DELETE FROM nx_withdrawal_order");
        try (var insert = connection.prepareStatement("INSERT INTO nx_withdrawal_order VALUES(1,7,?,0)")) {
            insert.setString(1, status);
            insert.executeUpdate();
        }
        // Neither another account's active withdrawal nor a deleted own row may affect this caller.
        execute(connection, "INSERT INTO nx_withdrawal_order VALUES(2,8,'SENT',0),(3,7,'SENT',1)");
    }

    private static String jdbcUrl(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}"))) {
            throw new IllegalArgumentException("owned UUID schema required");
        }
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    }

    private static String url(String schema) {
        return jdbcUrl(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema);
    }

    private static String password() {
        return System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
    }

    private static Connection connect(String schema) throws Exception {
        return DriverManager.getConnection(url(schema), "root", password());
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
