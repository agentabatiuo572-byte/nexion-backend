package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Real MySQL proof for the H8 effectiveAt boundary and App/settlement read-model parity. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferralRewardEffectiveAtMySqlIntegrationTest {
    private static final long INVITER = 100L;
    private static final long BEFORE = 101L;
    private static final long AT_BOUNDARY = 102L;
    private static final long HISTORICAL = 103L;
    private static final long SANDBOX_INVITER = 200L;
    private static final long SANDBOX_INVITED = 201L;
    private static final LocalDateTime EFFECTIVE_AT = LocalDateTime.of(2026, 9, 1, 0, 0);

    private Connection connection;
    private JdbcTemplate jdbc;
    private String fixtureDatabase;
    private boolean fixtureDatabaseCreated;
    private ReferralRewardMapper mapper;

    @BeforeAll
    void createOwnedFixtureDatabase() throws Exception {
        connection = DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        fixtureDatabase = "nx_h8_boundary_test_" + UUID.randomUUID().toString().replace("-", "");
        assertOwnedFixtureDatabase();
        try {
            jdbc.execute("CREATE DATABASE `" + fixtureDatabase
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            fixtureDatabaseCreated = true;
            jdbc.execute("USE `" + fixtureDatabase + "`");
            createSchema();
            Configuration configuration = new Configuration(new Environment("h8-boundary-test",
                    new SpringManagedTransactionFactory(), new SingleConnectionDataSource(connection, true)));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(ReferralRewardMapper.class);
            mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                    .getMapper(ReferralRewardMapper.class);
        } catch (Exception failure) {
            dropOwnedFixtureDatabase();
            throw failure;
        }
    }

    @BeforeEach
    void resetOnlyOwnedFixtures() {
        assertOwnedFixtureDatabase();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureDatabase);
        for (String table : List.of("nx_h8_sandbox_referral_settlement",
                "nx_earnings_release_entry", "nx_wallet_ledger",
                "nx_referral_reward_settlement", "nx_admin_risk_arbitrage_row",
                "nx_admin_risk_multi_account_cluster", "nx_user_wallet", "nx_user")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        user(INVITER, null, EFFECTIVE_AT.minusYears(1));
        user(BEFORE, INVITER, EFFECTIVE_AT.minusNanos(1_000_000));
        user(AT_BOUNDARY, INVITER, EFFECTIVE_AT);
        user(HISTORICAL, INVITER, EFFECTIVE_AT.minusDays(30));
        user(SANDBOX_INVITER, null, EFFECTIVE_AT.minusYears(1), 1);
        user(SANDBOX_INVITED, SANDBOX_INVITER, EFFECTIVE_AT, 1);
        for (long userId : List.of(INVITER, BEFORE, AT_BOUNDARY, HISTORICAL)) wallet(userId);
        wallet(SANDBOX_INVITER, 1);
        wallet(SANDBOX_INVITED, 1);
        settlement("HIST", HISTORICAL, "10.000000", EFFECTIVE_AT.minusDays(20));
        verifiedProjection("HIST", "10.000000", "10.000000", EFFECTIVE_AT.minusDays(20));
    }

    @AfterAll
    void discardOwnedFixtureDatabase() throws Exception {
        if (connection == null) return;
        try {
            dropOwnedFixtureDatabase();
        } finally {
            connection.close();
        }
    }

    @Test
    void excludesTheLastMillisecondBeforeEffectiveAtAndIncludesTheExactBoundary() {
        assertThat(mapper.findPendingReferrals(
                EFFECTIVE_AT, "PRODUCTION", 0, true, 10, null))
                .extracting(ReferralRewardMapper.ReferralRow::invitedUserId)
                .containsExactly(AT_BOUNDARY);
        assertThat(mapper.appPendingCount(INVITER, EFFECTIVE_AT, "PRODUCTION", 0)).isOne();

        assertThat(insert("OLD", BEFORE, "5.000000")).isZero();
        assertThat(insert("NEW", AT_BOUNDARY, "7.000000")).isOne();
        verifiedProjection("NEW", "7.000000", "17.000000", EFFECTIVE_AT.plusMinutes(1));

        assertThat(jdbc.queryForList(
                "SELECT settlement_no FROM nx_referral_reward_settlement ORDER BY id", String.class))
                .containsExactly("HIST", "NEW");
        assertThat(mapper.appPendingCount(INVITER, EFFECTIVE_AT, "PRODUCTION", 0)).isZero();
        assertThat(mapper.appSettlementCount(INVITER, "PRODUCTION", 0)).isEqualTo(2);

        ReferralRewardMapper.AppReferralLedgerSummary summary = mapper.appVerifiedRewardSummary(
                INVITER, "H8_REFERRAL", "PRODUCTION", 0);
        assertThat(summary.settledCount()).isEqualTo(2);
        assertThat(summary.lifetimeInviterNex()).isEqualByComparingTo("17.000000");
        assertThat(mapper.appRecentVerifiedRewards(
                INVITER, "H8_REFERRAL", "PRODUCTION", 0, 10))
                .extracting(ReferralRewardMapper.AppReferralLedgerRow::settlementNo)
                .containsExactly("NEW", "HIST");
    }

    @Test
    void keepsProductionAndSandboxSettlementsInSeparateTablesOnRealMySql() {
        assertThat(mapper.findPendingReferrals(
                EFFECTIVE_AT, "PRODUCTION", 0, true, 10, null))
                .extracting(ReferralRewardMapper.ReferralRow::invitedUserId)
                .containsExactly(AT_BOUNDARY);
        assertThat(insert("PROD-REFUSES-SANDBOX", SANDBOX_INVITED, "7.000000")).isZero();

        assertThat(mapper.findPendingSandboxReferral(EFFECTIVE_AT, "run-boundary", null))
                .extracting(ReferralRewardMapper.ReferralRow::invitedUserId)
                .containsExactly(SANDBOX_INVITED);
        assertThat(mapper.insertSandboxSettlement(
                "SANDBOX-ONLY", SANDBOX_INVITED, SANDBOX_INVITER,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("7.000000"),
                "immediate", "{}", "test", "isolation proof",
                "idem-sandbox", "run-boundary", EFFECTIVE_AT)).isOne();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_referral_reward_settlement WHERE invited_user_id=?",
                Long.class, SANDBOX_INVITED)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_h8_sandbox_referral_settlement WHERE invited_user_id=? AND run_id=?",
                Long.class, SANDBOX_INVITED, "run-boundary")).isOne();
    }

    private int insert(String settlementNo, long invitedUserId, String inviterNex) {
        return mapper.insertSettlement(settlementNo, invitedUserId, INVITER,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(inviterNex),
                "immediate", "{}", "test", "boundary proof", "idem-" + settlementNo,
                EFFECTIVE_AT, "PRODUCTION", 0, true);
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE nx_user (
                  id BIGINT PRIMARY KEY, sponsor_user_id BIGINT NULL, sandbox TINYINT NOT NULL,
                  status VARCHAR(32) NOT NULL, created_at DATETIME(3) NOT NULL,
                  is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_user_wallet (
                  user_id BIGINT PRIMARY KEY, sandbox TINYINT NOT NULL,
                  nex_available DECIMAL(18,6) NOT NULL DEFAULT 0,
                  is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_referral_reward_settlement (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, settlement_no VARCHAR(96) NOT NULL UNIQUE,
                  invited_user_id BIGINT NOT NULL, inviter_user_id BIGINT NOT NULL,
                  newcomer_usdt DECIMAL(18,6) NOT NULL, newcomer_nex DECIMAL(18,6) NOT NULL,
                  inviter_nex DECIMAL(18,6) NOT NULL, lock_mode VARCHAR(32), config_snapshot TEXT,
                  operator VARCHAR(64), reason VARCHAR(255), idempotency_key VARCHAR(128) NOT NULL,
                  status VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL,
                  updated_at DATETIME(6) NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_h8_invited (invited_user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_h8_sandbox_referral_settlement (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, settlement_no VARCHAR(96) NOT NULL,
                  invited_user_id BIGINT NOT NULL, inviter_user_id BIGINT NOT NULL,
                  newcomer_usdt DECIMAL(18,6) NOT NULL, newcomer_nex DECIMAL(18,6) NOT NULL,
                  inviter_nex DECIMAL(18,6) NOT NULL, lock_mode VARCHAR(32), config_snapshot TEXT,
                  operator VARCHAR(64), reason VARCHAR(255), idempotency_key VARCHAR(128) NOT NULL,
                  run_id VARCHAR(96) NOT NULL, status VARCHAR(32) NOT NULL,
                  source VARCHAR(32) NOT NULL, source_environment VARCHAR(32) NOT NULL,
                  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
                  is_deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_h8_sandbox_invited_run (invited_user_id,run_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_wallet_ledger (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, biz_no VARCHAR(128) NOT NULL,
                  biz_type VARCHAR(64) NOT NULL, asset VARCHAR(16) NOT NULL, direction VARCHAR(16) NOT NULL,
                  amount DECIMAL(18,6) NOT NULL, balance_after DECIMAL(18,6) NOT NULL,
                  status VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL,
                  is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_earnings_release_entry (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, source_ref VARCHAR(160) NOT NULL,
                  asset VARCHAR(16) NOT NULL, amount DECIMAL(18,6) NOT NULL, status VARCHAR(32) NOT NULL,
                  source_type VARCHAR(64) NOT NULL, source_environment VARCHAR(32) NOT NULL,
                  bucket VARCHAR(32) NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_admin_risk_arbitrage_row (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, cluster_id VARCHAR(64), disposition VARCHAR(64),
                  cell1 VARCHAR(255), cell2 VARCHAR(255), cell3 VARCHAR(255),
                  cell4 VARCHAR(255), cell5 VARCHAR(255), cell6 VARCHAR(255),
                  is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE nx_admin_risk_multi_account_cluster (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, cluster_id VARCHAR(64), status VARCHAR(32),
                  nodes_json JSON NULL, is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void user(long id, Long sponsor, LocalDateTime createdAt) {
        user(id, sponsor, createdAt, 0);
    }

    private void user(long id, Long sponsor, LocalDateTime createdAt, int sandbox) {
        jdbc.update("INSERT INTO nx_user(id,sponsor_user_id,sandbox,status,created_at,is_deleted)"
                + " VALUES (?,?,?,'ACTIVE',?,0)", id, sponsor, sandbox, createdAt);
    }

    private void wallet(long userId) {
        wallet(userId, 0);
    }

    private void wallet(long userId, int sandbox) {
        jdbc.update("INSERT INTO nx_user_wallet(user_id,sandbox,nex_available,is_deleted) VALUES (?,?,0,0)",
                userId, sandbox);
    }

    private void settlement(String settlementNo, long invitedUserId, String inviterNex, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO nx_referral_reward_settlement("
                        + "settlement_no,invited_user_id,inviter_user_id,newcomer_usdt,newcomer_nex,inviter_nex,"
                        + "lock_mode,config_snapshot,operator,reason,idempotency_key,status,created_at,updated_at,is_deleted)"
                        + " VALUES (?,?,?,0,0,?,'immediate','{}','test','historical',?,'SETTLED',?,?,0)",
                settlementNo, invitedUserId, INVITER, new BigDecimal(inviterNex),
                "idem-" + settlementNo, createdAt, createdAt);
    }

    private void verifiedProjection(String settlementNo, String amount, String balance, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at,is_deleted)"
                        + " VALUES (?,?, 'REFERRAL_REWARD','NEX','IN',?,?,'SUCCESS',?,0)",
                INVITER, settlementNo + ":INVITER", new BigDecimal(amount), new BigDecimal(balance), createdAt);
        jdbc.update("INSERT INTO nx_earnings_release_entry(user_id,source_ref,asset,amount,status,source_type,source_environment,bucket,is_deleted)"
                        + " VALUES (?,?,'NEX',?,'ACTIVE','H8_REFERRAL','PRODUCTION','AVAILABLE',0)",
                INVITER, settlementNo + ":INVITER:NEX", new BigDecimal(amount));
    }

    private void assertOwnedFixtureDatabase() {
        assertThat(fixtureDatabase).startsWith("nx_h8_boundary_test_");
    }

    private void dropOwnedFixtureDatabase() {
        if (!fixtureDatabaseCreated || jdbc == null) return;
        assertOwnedFixtureDatabase();
        jdbc.execute("DROP DATABASE IF EXISTS `" + fixtureDatabase + "`");
        fixtureDatabaseCreated = false;
    }
}
