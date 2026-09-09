package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Executes real read queries on owned fixtures only; no settlement or ledger writes. */
@EnabledIfEnvironmentVariable(named = "NEXION_F16_LB_IT", matches = "(?i)true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderboardEligibilityMySqlIntegrationTest {
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime TO = FROM.plusDays(7);
    private static final BigDecimal MINIMUM = new BigDecimal("150");
    private static final String LOOPBACK_SERVER_URL =
            "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private Connection connection;
    private SqlSession session;
    private String fixtureDatabase;
    private boolean created;
    private TeamCommissionMapper settlement;
    private AppTeamInsightsMapper app;

    @BeforeAll
    void createOwnedFixtures() throws Exception {
        requireServerUrlWithoutCatalog(LOOPBACK_SERVER_URL);
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "NEXION_TEST_DB_PASSWORD is required after explicit F16 opt-in");
        connection = DriverManager.getConnection(LOOPBACK_SERVER_URL,
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"), password);
        fixtureDatabase = "nx_lb_eligibility_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            execute("CREATE DATABASE `" + fixtureDatabase + "`");
            created = true;
            connection.setCatalog(fixtureDatabase);
            assertFixtureCatalog();
            execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY, nickname VARCHAR(50), v_rank VARCHAR(10), sponsor_user_id BIGINT, status VARCHAR(20), is_deleted INT, sandbox INT)");
            execute("CREATE TABLE nx_commission_event(user_id BIGINT, amount_usdt DECIMAL(20,6), status VARCHAR(20), commission_type VARCHAR(30), created_at DATETIME, is_deleted INT)");
            execute("CREATE TABLE nx_team_leaderboard_action(period VARCHAR(20), member_user_id BIGINT, action_type VARCHAR(20), is_deleted INT)");
            execute("CREATE TABLE nx_team_member(user_id BIGINT, is_deleted INT)");
            execute("CREATE TABLE nx_user_device(user_id BIGINT, is_deleted INT)");
            execute("INSERT INTO nx_user VALUES (1,'one','V0',NULL,'ACTIVE',0,0),(2,'inactive','V0',NULL,'DISABLED',0,0),(3,'deleted','V0',NULL,'ACTIVE',1,0),(4,'sandbox','V0',NULL,'ACTIVE',0,1),(6,'below','V0',NULL,'ACTIVE',0,0),(7,'risk','V0',NULL,'ACTIVE',0,0),(8,'cooling','V0',NULL,'ACTIVE',0,0),(9,'outside','V0',NULL,'ACTIVE',0,0),(10,'tie','V0',NULL,'ACTIVE',0,0)");
            execute("INSERT INTO nx_commission_event VALUES (1,100,'UNLOCKED','unilevel','2026-09-02',0),(1,100,'UNLOCKED','binary','2026-09-03',0),(2,1000,'UNLOCKED','unilevel','2026-09-02',0),(3,900,'UNLOCKED','unilevel','2026-09-02',0),(4,800,'UNLOCKED','unilevel','2026-09-02',0),(5,700,'UNLOCKED','unilevel','2026-09-02',0),(6,100,'UNLOCKED','unilevel','2026-09-02',0),(7,600,'UNLOCKED','unilevel','2026-09-02',0),(8,500,'COOLING','unilevel','2026-09-02',0),(9,1000,'UNLOCKED','unilevel','2026-08-31',0),(10,200,'UNLOCKED','unilevel','2026-09-02',0)");
            execute("INSERT INTO nx_team_leaderboard_action VALUES ('week',7,'RISK',0)");
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(TeamCommissionMapper.class);
            configuration.addMapper(AppTeamInsightsMapper.class);
            session = new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
            settlement = session.getMapper(TeamCommissionMapper.class);
            app = session.getMapper(AppTeamInsightsMapper.class);
        } catch (Exception failure) {
            cleanup();
            throw failure;
        }
    }

    @Test
    void excludesInactiveDeletedSandboxOrphanAndIneligibleCommissionFacts() {
        var rows = settlement.leaderboardCandidatesByPeriod("week", FROM, TO, MINIMUM, 100);
        assertThat(ids(rows)).containsExactly(1L, 10L);
        assertThat((BigDecimal) rows.get(0).get("volume")).isEqualByComparingTo("200");
    }

    @Test
    void settlementAndProductionAppRankTheSameCandidates() {
        var expected = app.leaderboardEligible("week", 0, FROM, TO, MINIMUM, 100, TO)
                .stream().map(AppTeamInsightsMapper.LeaderboardRow::userId).toList();
        assertThat(expected).containsExactly(1L, 10L);
        assertThat(ids(settlement.leaderboardCandidatesByPeriod("week", FROM, TO, MINIMUM, 100)))
                .containsExactlyElementsOf(expected);
    }

    @Test
    void excludesIneligibleHighEarnersBeforeApplyingTheTopLimit() {
        assertThat(ids(settlement.leaderboardCandidatesByPeriod("week", FROM, TO, MINIMUM, 1)))
                .containsExactly(1L);
    }

    private List<Long> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> ((Number) row.get("userId")).longValue()).toList();
    }

    static void requireServerUrlWithoutCatalog(String url) {
        if (url == null || !url.matches("^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$")) {
            throw new IllegalArgumentException("F16 fixture requires a loopback MySQL server URL without a catalog");
        }
    }

    private void assertFixtureCatalog() throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DATABASE()")) {
            if (!rows.next() || !fixtureDatabase.equals(rows.getString(1))) {
                throw new IllegalStateException("F16 fixture connection is outside its owned schema");
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    @AfterAll
    void cleanup() throws Exception {
        if (connection == null || connection.isClosed()) return;
        try {
            if (created) {
                if (fixtureDatabase == null || !fixtureDatabase.matches("nx_lb_eligibility_test_[0-9a-f]{32}")) {
                    throw new IllegalStateException("Refusing to drop an unowned fixture database");
                }
                execute("DROP DATABASE `" + fixtureDatabase + "`");
                created = false;
            }
        } finally {
            if (session != null) session.close();
            if (!connection.isClosed()) connection.close();
        }
    }
}
