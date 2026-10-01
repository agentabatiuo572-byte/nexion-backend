package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the real annotated mapper against an owned schema on the isolated local MySQL fixture. */
@EnabledIfEnvironmentVariable(named = "NEXION_PROOF_EARNINGS_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppProofEarningsMySqlIntegrationTest {
    private static final long USER = 701L;
    private Connection connection;
    private JdbcTemplate jdbc;
    private Configuration configuration;
    private AppProofMapper mapper;
    private String schema;
    private boolean created;
    private int sequence;

    @BeforeAll
    void createOwnedFixture() throws Exception {
        assertThat(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")).isEqualTo("127.0.0.1:13306");
        schema = "nx_proof_earnings_test_" + UUID.randomUUID().toString().replace("-", "");
        assertSchemaName();
        connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:13306/"
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC", "root", "");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isNull();
        try {
            jdbc.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
            jdbc.execute("USE `" + schema + "`");
            assertOwnedFixture();
            jdbc.execute("""
                    CREATE TABLE nx_user (
                      id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL,
                      sandbox TINYINT NOT NULL DEFAULT 0, is_deleted TINYINT NOT NULL DEFAULT 0
                    )
                    """);
            // Minimal projection of scripts/schema.sql's ledger, including its actual ownership indexes.
            jdbc.execute("""
                    CREATE TABLE nx_wallet_ledger (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
                      biz_no VARCHAR(96) NOT NULL, biz_type VARCHAR(64) NOT NULL,
                      asset VARCHAR(16) NOT NULL, direction VARCHAR(16) NOT NULL,
                      amount DECIMAL(18,6) NOT NULL, status VARCHAR(32) NOT NULL,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, is_deleted TINYINT NOT NULL DEFAULT 0,
                      UNIQUE KEY uk_wallet_ledger_biz (biz_no,asset,direction),
                      KEY idx_wallet_ledger_user_time (user_id,created_at),
                      CONSTRAINT chk_wallet_ledger_positive_amount CHECK (amount>0)
                    )
                    """);
            // 20260807_nexion_hard_blockers plus 20260810_kl_janus_applied_proof's environment column.
            jdbc.execute("""
                    CREATE TABLE nx_earnings_release_entry (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, entry_no VARCHAR(64) NOT NULL,
                      user_id BIGINT NOT NULL, source_type VARCHAR(64) NOT NULL, source_ref VARCHAR(128) NOT NULL,
                      asset VARCHAR(16) NOT NULL, amount DECIMAL(24,6) NOT NULL,
                      bucket VARCHAR(24) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                      source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
                      idempotency_key VARCHAR(128) NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
                      UNIQUE KEY uk_earnings_release_entry_no (entry_no),
                      UNIQUE KEY uk_earnings_release_source (source_type,source_ref,user_id),
                      UNIQUE KEY uk_earnings_release_idem (idempotency_key),
                      KEY idx_earnings_release_user_bucket (user_id,bucket,status,is_deleted),
                      CONSTRAINT chk_earnings_release_amount CHECK (amount>0),
                      CONSTRAINT chk_earnings_release_bucket CHECK (bucket IN ('withdrawable','pending_review','bonus_locked'))
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE nx_staking_position (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
                      position_no VARCHAR(96) NOT NULL, product_code VARCHAR(64) NOT NULL,
                      amount_usdt DECIMAL(18,6) NOT NULL, estimated_interest_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
                      status VARCHAR(32) NOT NULL, unlock_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      is_deleted TINYINT NOT NULL DEFAULT 0,
                      UNIQUE KEY uk_staking_position_no (position_no),
                      KEY idx_staking_position_user_status (user_id,status,unlock_at),
                      CONSTRAINT chk_staking_position_positive_amount CHECK (amount_usdt>0)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE nx_earning_summary (
                      user_id BIGINT NOT NULL, summary_date DATE NOT NULL, usdt_amount DECIMAL(18,6) NOT NULL,
                      is_deleted TINYINT NOT NULL DEFAULT 0, UNIQUE KEY uk_earning_summary_user_date (user_id,summary_date)
                    )
                    """);
            configuration = new Configuration(new Environment("proof-owned-local-fixture",
                    new SpringManagedTransactionFactory(), new SingleConnectionDataSource(connection, true)));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(AppProofMapper.class);
            mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(AppProofMapper.class);
            System.out.println("Owned Proof fixture schema=" + schema + ", port=13306, MySQL="
                    + jdbc.queryForObject("SELECT VERSION()", String.class));
        } catch (Exception failure) {
            dropOwnedFixture();
            connection.close();
            throw failure;
        }
    }

    @BeforeEach
    void resetOwnedFixture() {
        assertOwnedFixture();
        for (String table : new String[]{"nx_wallet_ledger", "nx_earnings_release_entry", "nx_staking_position", "nx_earning_summary", "nx_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        sequence = 0;
        jdbc.update("INSERT INTO nx_user(id,status) VALUES (?, 'ACTIVE')", USER);
    }

    @Test
    void fiveObservedVietQrTopupsAreNotEarned() {
        for (int amount : new int[]{21, 47, 46, 34, 33}) ledger(USER, "DEPOSIT-" + amount, "VIETQR_DEPOSIT", "USDT", "IN", amount, "SUCCESS", 0);
        BigDecimal actual = mapper.earningsTotalUsdt(USER);
        System.out.println("Five observed-shape local topups: inbound=181, actual earned=" + actual);
        assertThat(actual).isEqualByComparingTo("0");
    }

    @Test
    void excludesOtherNonIncomeInflowsAndReturnedPrincipal() {
        for (String type : new String[]{"DEPOSIT", "TOPUP", "RECHARGE", "TRANSFER", "ORDER_REFUND", "WITHDRAW_PAYOUT_REFUND",
                "CHAIN_TOPUP", "CARD_TOPUP", "ADJUSTMENT", "EXCHANGE_SWAP", "WITHDRAW_REFUND",
                "EXCHANGE_CREDIT", "GENESIS_SECONDARY_SELL", "STAKING_EARLY_WITHDRAW", "STAKING_CLAIM",
                "REPURCHASE_CLAIM", "REPURCHASE_EARLY_WITHDRAW", "UNKNOWN_REWARD"}) {
            ledger(USER, type, type, "USDT", "IN", 10, "SUCCESS", 0);
        }
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("0");
    }

    @Test
    void preservesConfirmedPureRewardFamiliesAndExistingGrossInSemantics() {
        for (String status : new String[]{"SUCCESS", "POSTED", "CREDITED", "SETTLED", "AVAILABLE"}) {
            ledger(USER, status, "COMPUTE_TASK_REWARD", "usdt", "in", 1, status.toLowerCase(), 0);
        }
        ledger(USER, "TEAM", "TEAM_COMMISSION", "USDT", "IN", 2, "SUCCESS", 0);
        ledger(USER, "WHEEL", "WHEEL_REWARD", "USDT", "IN", 3, "POSTED", 0);
        ledger(USER, "EVENT", "EVENT_REWARD", "USDT", "IN", 4, "POSTED", 0);
        ledger(USER, "DAILY", "DAILY_MILESTONE", "USDT", "IN", 5, "POSTED", 0);
        // Preserve the current gross-IN contract. F5's separate OUT reversal is not a deposit fix.
        ledger(USER, "F5-REVERSE", "TEAM_COMMISSION", "USDT", "OUT", 2, "SUCCESS", 0);
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("19");
    }

    @Test
    void respectsOwnerAssetDeletionAndCreditStatus() {
        ledger(USER, "OWN", "COMPUTE_TASK_REWARD", "USDT", "IN", 2, "SUCCESS", 0);
        ledger(702, "OTHER", "TEAM_COMMISSION", "USDT", "IN", 999, "SUCCESS", 0);
        ledger(USER, "NEX", "TEAM_COMMISSION", "NEX", "IN", 777, "SUCCESS", 0);
        ledger(USER, "DELETED", "TEAM_COMMISSION", "USDT", "IN", 555, "SUCCESS", 1);
        for (String status : new String[]{"PENDING", "FAILED", "REJECTED", "CANCELLED", "REVERSED"}) {
            ledger(USER, status, "TEAM_COMMISSION", "USDT", "IN", 111, status, 0);
        }
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("2");
        assertThat(mapper.earningsTotalUsdt(703L)).isEqualByComparingTo("0");
    }

    @Test
    void preservesPureStakingInterestWithoutCountingReturnedPrincipalOrOrdinaryReleaseTwice() {
        ledger(USER, "COMPUTE", "COMPUTE_TASK_REWARD", "USDT", "IN", 2, "SUCCESS", 0);
        entry(USER, "COMPUTE", "COMPUTE_TASK_REWARD", "USDT", 2, "ACTIVE", "PRODUCTION", 0, "withdrawable");
        ledger(USER, "POSITION-CLAIM", "STAKING_CLAIM", "USDT", "IN", 110, "SUCCESS", 0);
        entry(USER, "POSITION", "staking_interest", "USDT", 10, "ACTIVE", "PRODUCTION", 0, "withdrawable");
        BigDecimal actual = mapper.earningsTotalUsdt(USER);
        System.out.println("Own compute2 plus staking principal100/interest10: actual earned=" + actual);
        assertThat(actual).isEqualByComparingTo("12");
    }

    @ParameterizedTest
    @MethodSource("protectedBuckets")
    void stakingIssuedInterestRemainsEarnedAcrossReleaseBuckets(String bucket) {
        ledger(USER, "POSITION-CLAIM", "STAKING_CLAIM", "USDT", "IN", 110, "SUCCESS", 0);
        entry(USER, "POSITION", "staking_interest", "USDT", 10, "ACTIVE", "PRODUCTION", 0, bucket);
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("10");
    }

    static Stream<String> protectedBuckets() { return Stream.of("withdrawable", "pending_review", "bonus_locked"); }

    @ParameterizedTest
    @MethodSource("unconfirmedInterest")
    void excludesInterestWithoutItsSameOwnerConfirmedProductionClaim(String invalid) {
        long entryUser = invalid.equals("entry owner") ? 702 : USER;
        long claimUser = invalid.equals("claim owner") ? 702 : USER;
        String ref = invalid.equals("reference") ? "OTHER-POSITION" : "POSITION";
        entry(entryUser, ref, invalid.equals("source") ? "OTHER_REWARD" : "staking_interest",
                invalid.equals("entry asset") ? "NEX" : "USDT", 10,
                invalid.equals("entry status") ? "CANCELLED" : "ACTIVE",
                invalid.equals("environment") ? "SANDBOX" : "PRODUCTION",
                invalid.equals("entry deleted") ? 1 : 0, "withdrawable");
        if (!invalid.equals("missing claim")) {
            ledger(claimUser, "POSITION-CLAIM", invalid.equals("claim type") ? "STAKING_EARLY_WITHDRAW" : "STAKING_CLAIM",
                    invalid.equals("claim asset") ? "NEX" : "USDT", invalid.equals("claim direction") ? "OUT" : "IN",
                    110, invalid.equals("claim status") ? "PENDING" : "SUCCESS", invalid.equals("claim deleted") ? 1 : 0);
        }
        assertThat(mapper.earningsTotalUsdt(USER)).as(invalid).isEqualByComparingTo("0");
    }

    static Stream<String> unconfirmedInterest() {
        return Stream.of("entry owner", "entry asset", "entry status", "environment", "entry deleted", "source",
                "reference", "missing claim", "claim owner", "claim asset", "claim direction", "claim status", "claim deleted", "claim type");
    }

    @ParameterizedTest
    @MethodSource("omittedRewardFamilies")
    void preservesExistingGenesisReferralAndTrialRewards(String family) {
        int amount = family.equals("GENESIS_EMISSION") ? 3 : family.equals("REFERRAL_REWARD") ? 4 : 5;
        String status = family.equals("TRIAL_BONUS") ? "POSTED" : "SUCCESS";
        ledger(USER, "REWARD", family, "USDT", "IN", amount, status, 0);
        ledger(702, "OTHER", family, "USDT", "IN", 999, status, 0);
        ledger(USER, "NEX", family, "NEX", "IN", 100, status, 0);
        ledger(USER, "DELETED", family, "USDT", "IN", 100, status, 1);
        ledger(USER, "PENDING", family, "USDT", "IN", 100, "PENDING", 0);
        ledger(USER, "OUT", family, "USDT", "OUT", 100, status, 0);
        BigDecimal actual = mapper.earningsTotalUsdt(USER);
        System.out.println("Historical legitimate " + family + " " + status + ": actual earned=" + actual);
        assertThat(actual).isEqualByComparingTo(Integer.toString(amount));
        if (family.equals("TRIAL_BONUS")) {
            ledger(USER, "HISTORICAL-SUCCESS", family, "USDT", "IN", amount, "SUCCESS", 0);
            assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("10");
        }
    }

    static Stream<String> omittedRewardFamilies() {
        return Stream.of("GENESIS_EMISSION", "REFERRAL_REWARD", "TRIAL_BONUS");
    }

    @Test
    void mixedHistoricalRewardsExcludeDepositsPrincipalAndOrdinaryReleaseDuplicates() {
        ledger(USER, "DEPOSIT", "VIETQR_DEPOSIT", "USDT", "IN", 181, "SUCCESS", 0);
        ledger(USER, "COMPUTE", "COMPUTE_TASK_REWARD", "USDT", "IN", 2, "SUCCESS", 0);
        ledger(USER, "STAKING-CLAIM", "STAKING_CLAIM", "USDT", "IN", 110, "SUCCESS", 0);
        entry(USER, "STAKING", "staking_interest", "USDT", 10, "ACTIVE", "PRODUCTION", 0, "withdrawable");
        ledger(USER, "GENESIS", "GENESIS_EMISSION", "USDT", "IN", 3, "SUCCESS", 0);
        ledger(USER, "REFERRAL", "REFERRAL_REWARD", "USDT", "IN", 4, "SUCCESS", 0);
        ledger(USER, "TRIAL", "TRIAL_BONUS", "USDT", "IN", 5, "POSTED", 0);
        entry(USER, "GENESIS", "G4_GENESIS_EMISSION", "USDT", 3, "ACTIVE", "PRODUCTION", 0, "withdrawable");
        entry(USER, "REFERRAL", "H8_REFERRAL", "USDT", 4, "ACTIVE", "PRODUCTION", 0, "bonus_locked");
        entry(USER, "TRIAL", "H2_TRIAL_REMAINDER", "USDT", 5, "ACTIVE", "PRODUCTION", 0, "pending_review");
        BigDecimal actual = mapper.earningsTotalUsdt(USER);
        System.out.println("Mixed deposit181/task2/staking interest10/genesis3/referral4/trial5: actual earned=" + actual);
        assertThat(actual).isEqualByComparingTo("24");
        position(USER, "REPURCHASE", "REPURCHASE_90D", 200, 6, "CLAIMED", 0);
        ledger(USER, "REPURCHASE-CLAIM", "REPURCHASE_CLAIM", "USDT", "IN", 206, "SUCCESS", 0);
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("30");
    }

    @Test
    void preservesOnlyClaimedRepurchaseInterestWithoutOverlappingStakingRelease() {
        position(USER, "REPURCHASE", "REPURCHASE_90D", 200, 6, "claimed", 0);
        ledger(USER, "REPURCHASE-CLAIM", "REPURCHASE_CLAIM", "USDT", "IN", 206, "SUCCESS", 0);
        // This release cannot match STAKING_CLAIM; it must not duplicate the position's interest.
        entry(USER, "REPURCHASE", "staking_interest", "USDT", 6, "ACTIVE", "PRODUCTION", 0, "withdrawable");
        BigDecimal actual = mapper.earningsTotalUsdt(USER);
        System.out.println("Repurchase principal200/interest6: actual earned=" + actual);
        assertThat(actual).isEqualByComparingTo("6");
        position(USER, "STAKING", "USDT_90D", 100, 10, "CLAIMED", 0);
        ledger(USER, "STAKING-CLAIM", "STAKING_CLAIM", "USDT", "IN", 110, "SUCCESS", 0);
        entry(USER, "STAKING", "staking_interest", "USDT", 10, "ACTIVE", "PRODUCTION", 0, "withdrawable");
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("16");
    }

    @Test
    void zeroRepurchaseInterestDoesNotTurnReturnedPrincipalIntoIncome() {
        position(USER, "REPURCHASE", "REPURCHASE_90D", 200, 0, "CLAIMED", 0);
        ledger(USER, "REPURCHASE-CLAIM", "REPURCHASE_CLAIM", "USDT", "IN", 200, "SUCCESS", 0);
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("0");
    }

    @Test
    void earlyRepurchaseReturnIsPrincipalAndForfeitsTheStoredInterest() {
        position(USER, "REPURCHASE", "REPURCHASE_90D", 200, 6, "EARLY_WITHDRAWN", 0);
        ledger(USER, "REPURCHASE-EARLY", "REPURCHASE_EARLY_WITHDRAW", "USDT", "IN", 190, "SUCCESS", 0);
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("0");
    }

    @Test
    void actualUniqueClaimKeyPreventsRepurchaseInterestMultiplication() {
        position(USER, "REPURCHASE", "REPURCHASE_90D", 200, 6, "CLAIMED", 0);
        ledger(USER, "REPURCHASE-CLAIM", "REPURCHASE_CLAIM", "USDT", "IN", 206, "SUCCESS", 0);
        assertThatThrownBy(() -> ledger(USER, "REPURCHASE-CLAIM", "REPURCHASE_CLAIM", "USDT", "IN", 206, "SUCCESS", 0))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("6");
    }

    @ParameterizedTest
    @MethodSource("unconfirmedRepurchase")
    void excludesRepurchaseInterestWithoutItsClaimedSameOwnerPositionAndConfirmedClaim(String invalid) {
        if (!invalid.equals("missing position")) {
            position(invalid.equals("position owner") ? 702 : USER,
                    invalid.equals("reference") ? "OTHER" : "REPURCHASE",
                    invalid.equals("product") ? "USDT_90D" : "REPURCHASE_90D", 200, 6,
                    invalid.equals("active") ? "ACTIVE" : invalid.equals("mature") ? "MATURE_UNCLAIMED"
                            : invalid.equals("early") ? "EARLY_WITHDRAWN" : "CLAIMED",
                    invalid.equals("position deleted") ? 1 : 0);
        }
        if (!invalid.equals("missing claim")) {
            ledger(invalid.equals("claim owner") ? 702 : USER, "REPURCHASE-CLAIM",
                    invalid.equals("claim type") ? "REPURCHASE_EARLY_WITHDRAW" : "REPURCHASE_CLAIM",
                    invalid.equals("claim asset") ? "NEX" : "USDT", invalid.equals("claim direction") ? "OUT" : "IN",
                    206, invalid.equals("claim status") ? "PENDING" : "SUCCESS", invalid.equals("claim deleted") ? 1 : 0);
        }
        assertThat(mapper.earningsTotalUsdt(USER)).as(invalid).isEqualByComparingTo("0");
    }

    static Stream<String> unconfirmedRepurchase() {
        return Stream.of("missing position", "position owner", "product", "active", "mature", "early",
                "position deleted", "reference", "missing claim", "claim owner", "claim type", "claim asset",
                "claim direction", "claim status", "claim deleted");
    }

    @Test
    void correctedOwnEarningsFeedTheUnchangedSummaryPercentileFallback() {
        ledger(USER, "DEPOSIT", "VIETQR_DEPOSIT", "USDT", "IN", 181, "SUCCESS", 0);
        for (long user = 702; user <= 705; user++) {
            jdbc.update("INSERT INTO nx_user(id,status) VALUES (?,'ACTIVE')", user);
            jdbc.update("INSERT INTO nx_earning_summary(user_id,summary_date,usdt_amount) VALUES (?,'2026-10-01',?)", user, user - 701);
        }
        BigDecimal actual = mapper.earningsTotalUsdt(USER);
        AppProofMapper.PercentileRow population = mapper.earningsPopulation(USER, actual);
        System.out.println("Existing summary fallback with corrected own earnings=" + actual + ", population=" + population);
        assertThat(actual).isEqualByComparingTo("0");
        assertThat(population).isEqualTo(new AppProofMapper.PercentileRow(4L, 5L));
    }

    @Test
    void preservesExistingSummaryTieAndActiveProductionUserRulesWithoutAssertingFreshness() {
        for (long user = 702; user <= 707; user++) {
            jdbc.update("INSERT INTO nx_user(id,status,sandbox,is_deleted) VALUES (?,?,?,?)", user,
                    user == 707 ? "DISABLED" : "ACTIVE", user == 705 ? 1 : 0, user == 706 ? 1 : 0);
            jdbc.update("INSERT INTO nx_earning_summary(user_id,summary_date,usdt_amount) VALUES (?,'2026-10-01',?)", user,
                    user == 702 ? 5 : user == 703 ? 6 : 100);
        }
        jdbc.update("UPDATE nx_earning_summary SET is_deleted=1 WHERE user_id=704");
        assertThat(mapper.earningsPopulation(USER, new BigDecimal("5"))).isEqualTo(new AppProofMapper.PercentileRow(1L, 3L));
    }

    @Test
    void recordsActualOwnUserExplainWithOtherAccountsPresent() {
        for (int n = 0; n < 1000; n++) {
            ledger(999, "OTHER-" + n, "COMPUTE_TASK_REWARD", "USDT", "IN", 1, "SUCCESS", 0);
            entry(999, "OTHER-" + n, "staking_interest", "USDT", 1, "ACTIVE", "PRODUCTION", 0, "withdrawable");
            position(999, "OTHER-" + n, "REPURCHASE_90D", 100, 1, "CLAIMED", 0);
        }
        ledger(USER, "OWN", "COMPUTE_TASK_REWARD", "USDT", "IN", 2, "SUCCESS", 0);
        ledger(USER, "POSITION-CLAIM", "STAKING_CLAIM", "USDT", "IN", 110, "SUCCESS", 0);
        entry(USER, "POSITION", "staking_interest", "USDT", 10, "ACTIVE", "PRODUCTION", 0, "withdrawable");
        position(USER, "REPURCHASE", "REPURCHASE_90D", 200, 6, "CLAIMED", 0);
        ledger(USER, "REPURCHASE-CLAIM", "REPURCHASE_CLAIM", "USDT", "IN", 206, "SUCCESS", 0);
        jdbc.execute("ANALYZE TABLE nx_wallet_ledger, nx_earnings_release_entry");
        var sql = configuration.getMappedStatement(AppProofMapper.class.getName() + ".earningsTotalUsdt")
                .getBoundSql(Map.of("userId", USER));
        Object[] params = Collections.nCopies(sql.getParameterMappings().size(), USER).toArray();
        System.out.println("Actual own-user SQL=" + sql.getSql());
        System.out.println("Actual own-user EXPLAIN=" + jdbc.queryForList("EXPLAIN " + sql.getSql(), params));
        assertThat(mapper.earningsTotalUsdt(USER)).isEqualByComparingTo("18");
    }

    private void ledger(long user, String ref, String type, String asset, String direction, int amount, String status, int deleted) {
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,status,is_deleted) VALUES (?,?,?,?,?,?,?,?)",
                user, ref, type, asset, direction, amount, status, deleted);
    }

    private void entry(long user, String ref, String source, String asset, int amount, String status, String environment, int deleted, String bucket) {
        int number = ++sequence;
        jdbc.update("INSERT INTO nx_earnings_release_entry(entry_no,user_id,source_type,source_ref,asset,amount,bucket,status,source_environment,idempotency_key,is_deleted) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "ENTRY-" + number, user, source, ref, asset, amount, bucket, status, environment, "IDEM-" + number, deleted);
    }

    private void position(long user, String ref, String product, int principal, int interest, String status, int deleted) {
        jdbc.update("INSERT INTO nx_staking_position(user_id,position_no,product_code,amount_usdt,estimated_interest_usdt,status,is_deleted) VALUES (?,?,?,?,?,?,?)",
                user, ref, product, principal, interest, status, deleted);
    }

    private void assertSchemaName() { assertThat(schema).matches("nx_proof_earnings_test_[0-9a-f]{32}"); }
    private void assertOwnedFixture() {
        assertSchemaName();
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
    }
    @AfterAll
    void dropOwnedFixture() throws Exception {
        try {
            if (created) {
                assertOwnedFixture();
                jdbc.execute("USE information_schema");
                jdbc.execute("DROP DATABASE `" + schema + "`");
                created = false;
                System.out.println("Dropped only owned Proof fixture schema=" + schema);
            }
        } finally { if (connection != null) connection.close(); }
    }
}
