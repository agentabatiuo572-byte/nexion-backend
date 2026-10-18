package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Executes the production summary against a disposable schema on an explicitly owned local server. */
@EnabledIfEnvironmentVariable(named = "NEXION_F3_RESIDUAL_TEST_SERVER_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamCommissionMapperBinaryResidualMySqlIntegrationTest {
    private Connection admin;
    private String schema;
    private JdbcTemplate jdbc;
    private TeamCommissionMapper mapper;
    private LocalDate today;

    @BeforeAll
    void openFixture() throws Exception {
        String server = System.getenv("NEXION_F3_RESIDUAL_TEST_SERVER_URL");
        TeamCommissionMapperVRankMySqlIntegrationTest.requireServerUrlWithoutCatalog(server);
        admin = DriverManager.getConnection(server, "root", "");
        schema = "nx_f3_residual_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "`");
        }
        int query = server.indexOf('?');
        String url = server.substring(0, query < 0 ? server.length() : query) + schema
                + (query < 0 ? "" : server.substring(query));
        // DB session remains on the previous day/month while the engine business day is Oct1.
        var source = new DelegatingDataSource(new DriverManagerDataSource(url, "root", "")) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var statement = connection.createStatement()) {
                    statement.execute("SET time_zone='+07:00'");
                    statement.execute("SET timestamp=" + Instant.parse("2026-09-30T16:30:00Z").getEpochSecond());
                }
                return connection;
            }
        };
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(128),config_value VARCHAR(128),status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_binary_paid_order_volume(id BIGINT PRIMARY KEY,owner_user_id BIGINT,leg VARCHAR(1),amount_usdt DECIMAL(18,6),paid_at DATETIME,status VARCHAR(32),is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_binary_commission_settlement(id BIGINT PRIMARY KEY,user_id BIGINT,settlement_date DATE,left_volume DECIMAL(18,6),right_volume DECIMAL(18,6),matched_volume DECIMAL(18,6),amount_usdt DECIMAL(18,6),daily_cap_usdt DECIMAL(18,6),status VARCHAR(32),is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_binary_volume_cursor(owner_user_id BIGINT,through_volume_id_inclusive BIGINT)");
        jdbc.execute("CREATE TABLE nx_binary_leg_assignment(owner_user_id BIGINT,member_user_id BIGINT,assigned_by VARCHAR(64),assigned_by_admin_id BIGINT,assigned_at DATETIME)");
        jdbc.execute("CREATE TABLE nx_audit_log(action VARCHAR(96),resource_type VARCHAR(64),resource_id VARCHAR(128),biz_no VARCHAR(128),user_id BIGINT,actor_type VARCHAR(32),actor_id BIGINT,actor_username VARCHAR(128),result VARCHAR(32),is_deleted INT,created_at DATETIME)");
        Configuration configuration = new Configuration(new Environment("f3-residual", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(TeamCommissionMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(TeamCommissionMapper.class);
        today = LocalDate.of(2026, 10, 1);
        assertThat(jdbc.queryForObject("SELECT CURDATE()", LocalDate.class)).isEqualTo(today.minusDays(1));
    }

    @AfterAll
    void closeFixture() throws Exception {
        if (admin == null) return;
        try (var connection = admin; var statement = connection.createStatement()) {
            if (schema != null && schema.matches("nx_f3_residual_test_[0-9a-f]{32}"))
                statement.execute("DROP DATABASE `" + schema + "`");
        }
    }

    @BeforeEach
    void resetFixture() {
        for (String table : new String[]{"nx_config_item", "nx_binary_paid_order_volume", "nx_binary_commission_settlement", "nx_binary_volume_cursor"})
            jdbc.update("DELETE FROM " + table);
        policy("转结");
    }

    @Test
    void carryUsesCurrentCapturedBalanceInsteadOfAddingHistoricalResidualSnapshots() {
        volume(1, 7, "A", "4248.55", today, "ACTIVE", 0);
        volume(2, 7, "B", "4991.95", today, "ACTIVE", 0);
        settlement(3, 7, today.minusDays(1), "2583.8", "2617.9", "2583.8", "PENDING", 0);
        settlement(4, 7, today, "1664.75", "2408.15", "1664.75", "PENDING", 0);
        assertThat(pool()).isEqualTo(743);
        // A later capture must be reflected even though there is no newer settlement snapshot.
        volume(5, 7, "B", "100", today, "ACTIVE", 0);
        assertThat(pool()).isEqualTo(843);
    }

    @Test
    void monthlyResetUsesOnlyCurrentMonthAndCurrentConsumption() {
        policy("每月清零");
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        volume(1, 7, "A", "9000", lastMonth, "ACTIVE", 0);
        volume(2, 7, "B", "10000", lastMonth, "ACTIVE", 0);
        settlement(1, 7, lastMonth, "9000", "10000", "9000", "PENDING", 0);
        volume(3, 7, "A", "1100", today, "ACTIVE", 0);
        volume(4, 7, "B", "1400", today, "ACTIVE", 0);
        settlement(2, 7, today, "1100", "1400", "1000", "PENDING", 0);
        assertThat(pool()).isEqualTo(400);
        policy("转结");
        assertThat(pool()).isEqualTo(1400);
    }

    @Test
    void perPairClearDiscardsTheWholeCursorRangeAndKeepsOnlyNewCurrentMonthVolumes() {
        policy("每次对碰清零");
        volume(1, 7, "A", "1100", today, "ACTIVE", 0);
        volume(2, 7, "B", "1400", today, "ACTIVE", 0);
        settlement(1, 7, today, "1100", "1400", "1000", "PENDING", 0);
        jdbc.update("INSERT INTO nx_binary_volume_cursor VALUES (7,2)");
        volume(3, 7, "A", "200", today, "ACTIVE", 0);
        volume(4, 7, "B", "500", today, "ACTIVE", 0);
        volume(5, 7, "B", "9000", today.withDayOfMonth(1).minusDays(1), "ACTIVE", 0);
        assertThat(pool()).isEqualTo(500);
    }

    @Test
    void isolatesOwnersAndIgnoresDeletedBlockedVoidReversalAndFutureInputsThenRoundsOnce() {
        for (long owner : new long[]{7, 8}) {
            volume(owner, owner, "A", "10", today, "ACTIVE", 0);
            volume(owner + 10, owner, "B", "10.4", today, "ACTIVE", 0);
            settlement(owner, owner, today, "10", "10.4", "10", "PENDING", 0);
        }
        volume(30, 7, "B", "1000", today, "VOID", 0);
        volume(31, 7, "B", "1000", today, "REVERSAL_REQUIRED", 0);
        volume(32, 7, "B", "1000", today, "ACTIVE", 1);
        volume(33, 7, "B", "1000", today.plusDays(1), "ACTIVE", 0);
        settlement(30, 7, today, "9000", "9000", "9000", "BLOCKED", 0);
        settlement(31, 7, today, "9000", "9000", "9000", "PENDING", 1);
        settlement(32, 7, today.plusDays(1), "9000", "9000", "9000", "PENDING", 0);
        volume(40, 9, "A", "0.2", today, "ACTIVE", 0);
        volume(41, 9, "B", "0.2", today, "ACTIVE", 0);
        settlement(40, 9, today, "1000", "1000", "1000", "PENDING", 0);
        assertThat(pool()).isEqualTo(1);
        jdbc.update("DELETE FROM nx_binary_paid_order_volume");
        assertThat(pool()).isZero(); // old snapshots cannot manufacture a current pool
    }

    @Test
    void absentInactiveOrInvalidPolicyHasNoNumericPoolInsteadOfInventingADefault() {
        volume(1, 7, "B", "700", today, "ACTIVE", 0);
        for (String invalid : new String[]{"", "unknown"}) {
            policy(invalid);
            assertThat(mapper.binarySettlementSummary().get("residualPoolUsd")).isNull();
        }
        policy("转结");
        jdbc.update("UPDATE nx_config_item SET status=0");
        assertThat(mapper.binarySettlementSummary().get("residualPoolUsd")).isNull();
        jdbc.update("DELETE FROM nx_config_item");
        assertThat(mapper.binarySettlementSummary().get("residualPoolUsd")).isNull();
    }

    private long pool() { return ((Number) mapper.binarySettlementSummary().get("residualPoolUsd")).longValue(); }
    private void policy(String value) {
        jdbc.update("DELETE FROM nx_config_item");
        jdbc.update("INSERT INTO nx_config_item VALUES ('team.ui.F.binary.residualPolicy',?,1,0)", value);
    }
    private void volume(long id, long owner, String leg, String amount, LocalDate date, String status, int deleted) {
        jdbc.update("INSERT INTO nx_binary_paid_order_volume VALUES (?,?,?,?,?,?,?)", id, owner, leg, amount, date.atTime(12, 0), status, deleted);
    }
    private void settlement(long id, long owner, LocalDate date, String left, String right, String matched, String status, int deleted) {
        jdbc.update("INSERT INTO nx_binary_commission_settlement VALUES (?,?,?,?,?,?,0,5000,?,?)", id, owner, date, left, right, matched, status, deleted);
    }
}
