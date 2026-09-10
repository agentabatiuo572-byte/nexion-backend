package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the production issued-ticket SUM mapper in a disposable isolated MySQL schema. */
class StakingMapperIssuedRepurchaseTicketsMySqlTest {
    private static final String PREFIX = "nexion_repurchase_ticket_it_";

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
    @EnabledIfEnvironmentVariable(named = "NEXION_REPURCHASE_TICKETS_IT", matches = "true")
    void issuedTicketSumKeepsForfeitedHistoryAndExcludesOnlyOldOrDeletedRows() throws Exception {
        inSchema(schema -> {
            try (Connection fixture = connect(schema)) {
                assertSelectedSchema(fixture, schema);
                createSchema(fixture);
                LocalDateTime monthStart = LocalDateTime.of(2026, 9, 1, 0, 0);
                fixture(fixture, monthStart);
                Configuration configuration = new Configuration(new Environment("isolated-repurchase-tickets",
                        new JdbcTransactionFactory(), new DriverManagerDataSource(url(schema), "root", password())));
                configuration.addMapper(StakingMapper.class);
                try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                    StakingMapper mapper = session.getMapper(StakingMapper.class);
                    assertThat(mapper.issuedRepurchaseTicketsSince(monthStart)).isEqualTo(8L);

                    // Product rules may change after issuance, and an early exit forfeits rather than deletes a ticket.
                    execute(fixture, "UPDATE nx_staking_product SET ticket_per_order=99 WHERE id=1");
                    execute(fixture, "UPDATE nx_g7_repurchase_ticket SET status='FORFEITED' WHERE id=1");
                    session.clearCache();
                    assertThat(mapper.issuedRepurchaseTicketsSince(monthStart)).isEqualTo(8L);

                    execute(fixture, "DELETE FROM nx_g7_repurchase_ticket");
                    session.clearCache();
                    assertThat(mapper.issuedRepurchaseTicketsSince(monthStart)).isZero();
                }
            }
        });
    }

    private static void createSchema(Connection connection) throws Exception {
        execute(connection, "CREATE TABLE nx_g7_repurchase_ticket (id BIGINT PRIMARY KEY,ticket_no VARCHAR(96) NOT NULL,user_id BIGINT NOT NULL,position_no VARCHAR(96) NOT NULL,quantity INT NOT NULL,status VARCHAR(32) NOT NULL,issued_at DATETIME NOT NULL,forfeited_at DATETIME NULL,is_deleted TINYINT NOT NULL) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE nx_staking_product (id BIGINT PRIMARY KEY,ticket_per_order INT NOT NULL) ENGINE=InnoDB");
    }

    private static void fixture(Connection connection, LocalDateTime monthStart) throws Exception {
        execute(connection, "INSERT INTO nx_staking_product VALUES (1,2)");
        ticket(connection, 1, "T-AVAILABLE", 3, "AVAILABLE", monthStart, 0);
        ticket(connection, 2, "T-FORFEITED", 5, "FORFEITED", monthStart.plusDays(2), 0);
        ticket(connection, 3, "T-OLD", 7, "AVAILABLE", monthStart.minusSeconds(1), 0);
        ticket(connection, 4, "T-DELETED", 11, "AVAILABLE", monthStart.plusDays(3), 1);
    }

    private static void ticket(Connection connection, long id, String ticketNo, int quantity, String status,
                               LocalDateTime issuedAt, int isDeleted) throws Exception {
        try (var insert = connection.prepareStatement("INSERT INTO nx_g7_repurchase_ticket VALUES (?,?,?,?,?,?,?,NULL,?)")) {
            insert.setLong(1, id); insert.setString(2, ticketNo); insert.setLong(3, 7); insert.setString(4, "P-" + id);
            insert.setInt(5, quantity); insert.setString(6, status); insert.setObject(7, issuedAt); insert.setInt(8, isDeleted);
            insert.executeUpdate();
        }
    }

    private static void assertSelectedSchema(Connection connection, String schema) throws Exception {
        try (var statement = connection.createStatement(); var selected = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(selected.next()).isTrue();
            assertThat(selected.getString(1)).isEqualTo(schema);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
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

    private static void inSchema(SchemaTest test) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            try (var statement = admin.createStatement(); var port = statement.executeQuery("SELECT @@port")) {
                assertThat(port.next()).isTrue();
                assertThat(port.getInt(1)).isEqualTo(13306);
            }
            execute(admin, "CREATE DATABASE " + schema);
            try { test.run(schema); }
            finally { execute(admin, "DROP DATABASE " + schema); }
        }
    }

    @FunctionalInterface
    private interface SchemaTest { void run(String schema) throws Exception; }
}
