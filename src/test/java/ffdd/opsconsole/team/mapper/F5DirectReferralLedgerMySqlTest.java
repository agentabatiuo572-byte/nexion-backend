package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the complete production annotation against synthetic rows in an owned MySQL schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class F5DirectReferralLedgerMySqlTest {
    private Connection admin;
    private String schema;
    private boolean created;
    private JdbcTemplate jdbc;
    private F5CommissionMapper mapper;

    @BeforeAll void openFixture() throws Exception {
        String url = System.getenv("NEXION_TEST_DB_SERVER_URL");
        TeamCommissionMapperVRankMySqlIntegrationTest.requireServerUrlWithoutCatalog(url);
        String user = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        admin = DriverManager.getConnection(url, user, password);
        schema = "nx_f5_ledger_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        System.out.println("F5_LEDGER_FIXTURE_CREATE=" + schema);
        int query = url.indexOf('?');
        String fixtureUrl = url.substring(0, query < 0 ? url.length() : query) + schema + (query < 0 ? "" : url.substring(query));
        var source = new DriverManagerDataSource(fixtureUrl, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,created_at DATETIME,is_deleted INT DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE nx_commission_event(id BIGINT PRIMARY KEY,user_id BIGINT,commission_type VARCHAR(32),
              currency VARCHAR(8),amount_usdt DECIMAL(24,6) DEFAULT 0,amount_nex DECIMAL(24,6) DEFAULT 0,
              source_user_id BIGINT,layer_no INT DEFAULT 1,order_no VARCHAR(80),status VARCHAR(32) DEFAULT 'UNLOCKED',
              version INT DEFAULT 0,frozen_from_status VARCHAR(32),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,unlock_at DATETIME,is_deleted INT DEFAULT 0)
            """);
        jdbc.execute("""
            CREATE TABLE nx_direct_referral_settlement(id BIGINT PRIMARY KEY AUTO_INCREMENT,settlement_no VARCHAR(80),
              beneficiary_user_id BIGINT,source_type VARCHAR(32),usdt_event_id BIGINT,nex_event_id BIGINT)
            """);
        jdbc.execute("""
            CREATE TABLE nx_wallet_ledger(id BIGINT PRIMARY KEY,biz_no VARCHAR(150),user_id BIGINT,
              biz_type VARCHAR(32),asset VARCHAR(8),direction VARCHAR(8),status VARCHAR(16),remark VARCHAR(255),is_deleted INT DEFAULT 0)
            """);
        jdbc.execute("CREATE TABLE nx_binary_commission_settlement(commission_event_id BIGINT,settlement_date DATE,is_deleted INT DEFAULT 0)");
        var config = new Configuration(new Environment("f5-ledger", new SpringManagedTransactionFactory(), source));
        config.addMapper(F5CommissionMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(F5CommissionMapper.class);
    }

    @BeforeEach void clear() {
        for (String table : List.of("nx_wallet_ledger", "nx_direct_referral_settlement", "nx_commission_event", "nx_user", "nx_binary_commission_settlement"))
            jdbc.execute("DELETE FROM " + table);
        jdbc.update("INSERT INTO nx_user(id,created_at) VALUES(3778,'2026-10-01')");
    }

    @AfterAll void closeFixture() throws Exception {
        if (admin == null) return;
        try {
            if (created) try (var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE `" + schema + "`");
                System.out.println("F5_LEDGER_FIXTURE_DROP=" + schema);
            }
        } finally { admin.close(); }
    }

    @Test void dualCurrencyReleaseLinksBothEventsOnceAndPreservesPaginationCount() {
        event(395, "direct_purchase", "USDT"); event(396, "direct_purchase", "UVT");
        group("DR-PAIR", 3778, 395L, 396L);
        ledger(1, "DR-PAIR-RELEASE", 3778, "USDT", "IN");
        ledger(2, "DR-PAIR-RELEASE", 3778, "UVT", "IN");
        assertThat(rows()).hasSize(2).allSatisfy(row -> assertThat(row.get("ledgerBizNo")).isEqualTo("DR-PAIR-RELEASE"));
        assertThat(mapper.countEvents(null, null, 3778L, null, null)).isEqualTo(2);
        assertThat(mapper.queryEvents(null, null, 3778L, null, null, 396L, 1)).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("eventId")).longValue()).isEqualTo(395));
        jdbc.update("DELETE FROM nx_wallet_ledger WHERE asset='UVT'");
        assertThat(biz(395)).isEqualTo("DR-PAIR-RELEASE");
        assertThat(biz(396)).isNull(); // USDT entry must not stand in for UVT.
        jdbc.update("UPDATE nx_commission_event SET is_deleted=1 WHERE id=395");
        assertThat(rows()).singleElement().satisfies(row -> assertThat(((Number) row.get("eventId")).longValue()).isEqualTo(396));
    }

    @Test void unrelatedGroupUserCurrencySlotDeletedAndMissingLedgerCannotLink() {
        event(395, "direct_purchase", "USDT");
        group("DR-WRONG-EVENT", 3778, 3950L, null);
        group("DR-WRONG-USER", 9988, 395L, null);
        group("DR-WRONG-SLOT", 3778, null, 395L);
        group("DR-RIGHT", 3778, 395L, null);
        ledger(1, "DR-WRONG-EVENT-RELEASE", 3778, "USDT", "IN");
        ledger(2, "DR-WRONG-USER-RELEASE", 3778, "USDT", "IN");
        ledger(3, "DR-WRONG-SLOT-RELEASE", 3778, "USDT", "IN");
        ledger(4, "DR-RIGHT-RELEASE", 9988, "USDT", "IN");
        ledger(5, "DR-RIGHT-RELEASE", 3778, "UVT", "IN");
        ledger(6, "DR-RIGHT-RELEASE", 3778, "USDT", "IN");
        jdbc.update("UPDATE nx_wallet_ledger SET is_deleted=1 WHERE id=6");
        group("DR-WRONG-KIND", 3778, 395L, null);
        jdbc.update("UPDATE nx_direct_referral_settlement SET source_type='network' WHERE settlement_no='DR-WRONG-KIND'");
        ledger(8, "DR-WRONG-KIND-RELEASE", 3778, "USDT", "IN");
        assertThat(biz(395)).isNull();
        ledger(7, "DR-RIGHT-RELEASE", 3778, "USDT", "IN");
        assertThat(biz(395)).isEqualTo("DR-RIGHT-RELEASE");
        jdbc.update("DELETE FROM nx_wallet_ledger");
        assertThat(biz(395)).isNull();
    }

    @Test void recoveryUsesOwnAssetExactGroupAndLatestActualEntry() {
        event(395, "direct_device_earning", "USDT"); event(396, "direct_device_earning", "UVT");
        group("DR-REC", 3778, 395L, 396L);
        ledger(1, "DR-REC-RELEASE", 3778, "USDT", "IN");
        ledger(2, "DR-REC-RECOVER-USDT-0.5", 3778, "USDT", "OUT");
        ledger(3, "DR-REC-RECOVER-USDT-0.9", 3778, "USDT", "OUT");
        ledger(4, "DR-REC-RECOVER-NEX-7.62", 3778, "UVT", "OUT");
        ledger(5, "DR-REC-OTHER-RECOVER-USDT-9", 3778, "USDT", "OUT");
        ledger(6, "DR-REC-RECOVER-NEX-999", 3778, "USDT", "OUT");
        ledger(7, "DR-REC-RECOVER-USDT-", 3778, "USDT", "OUT");
        ledger(8, "DR-REC-RECOVER-USDT-garbage", 3778, "USDT", "OUT");
        assertThat(biz(395)).isEqualTo("DR-REC-RECOVER-USDT-0.9");
        assertThat(biz(396)).isEqualTo("DR-REC-RECOVER-NEX-7.62");
    }

    @Test void wrongDirectionAndNonCommissionLedgerDoNotBecomeEvidence() {
        event(395, "direct_purchase", "USDT"); group("DR-DIR", 3778, 395L, null);
        ledger(1, "DR-DIR-RELEASE", 3778, "USDT", "OUT");
        ledger(2, "DR-DIR-RECOVER-USDT-1", 3778, "USDT", "IN");
        ledger(3, "DR-DIR-RELEASE", 3778, "USDT", "IN");
        jdbc.update("UPDATE nx_wallet_ledger SET biz_type='OTHER' WHERE id=3");
        ledger(4, "DR-DIR-RELEASE", 3778, "USDT", "IN");
        jdbc.update("UPDATE nx_wallet_ledger SET status='FAILED' WHERE id=4");
        assertThat(biz(395)).isNull();
    }

    @Test void legacyNetworkAndF5ReleaseReverseAndReissueRemainAvailable() {
        event(101, "network", "USDT"); event(102, "network", "UVT");
        event(103, "peer", "USDT"); event(104, "peer", "USDT"); event(105, "peer", "USDT");
        ledger(1, "F2-NETWORK-101", 3778, "USDT", "IN");
        ledger(2, "F2-NETWORK-NEX-102", 3778, "UVT", "IN");
        ledger(3, "F5-COMMISSION-103-RELEASE", 3778, "USDT", "IN");
        ledger(4, "F5-COMMISSION-104-REVERSE", 3778, "USDT", "OUT");
        ledger(5, "F5-REISSUE-105", 3778, "USDT", "IN");
        assertThat(biz(101)).isEqualTo("F2-NETWORK-101");
        assertThat(biz(102)).isEqualTo("F2-NETWORK-NEX-102");
        assertThat(biz(103)).isEqualTo("F5-COMMISSION-103-RELEASE");
        assertThat(biz(104)).isEqualTo("F5-COMMISSION-104-REVERSE");
        assertThat(biz(105)).isEqualTo("F5-REISSUE-105");
        event(106, "network", "USDT"); group("DR-NETWORK", 3778, 106L, null);
        ledger(6, "DR-NETWORK-RELEASE", 3778, "USDT", "IN");
        assertThat(biz(106)).isEqualTo("DR-NETWORK-RELEASE");
    }

    @Test void legitimateIndependentRewardsDoNotBlockOrLeakIntoF5EightKindReads() {
        event(110, "leaderboard_prize", "USDT");
        event(95, "leaderboard_prize", "USDT");
        event(85, "vrank_reward", "USDT");
        jdbc.update("UPDATE nx_commission_event SET amount_usdt=999");
        assertThat(mapper.unknownCommissionKindCount()).isZero();
        assertThat(rows()).isEmpty();
        assertThat(mapper.countEvents(null,null,null,null,null)).isZero();
        assertThat(mapper.queryExportEvents(null,null,null,null,null,null,100)).isEmpty();
        assertThat(mapper.aggregateCommissionKinds()).isEmpty();
        assertThat(mapper.aggregateCommissionStatuses()).isEmpty();

        event(100, "direct_purchase", "USDT"); event(90, "leadership", "USDT"); event(80, "cultivation", "UVT");
        jdbc.update("UPDATE nx_commission_event SET amount_usdt=10 WHERE id=100");
        jdbc.update("UPDATE nx_commission_event SET amount_usdt=20 WHERE id=90");
        jdbc.update("UPDATE nx_commission_event SET amount_nex=50 WHERE id=80");
        assertThat(mapper.unknownCommissionKindCount()).isZero();
        assertThat(mapper.queryEvents(null,null,null,null,null,null,2))
                .extracting(row -> ((Number)row.get("eventId")).longValue()).containsExactly(100L,90L);
        assertThat(mapper.queryEvents(null,null,null,null,null,90L,2))
                .extracting(row -> ((Number)row.get("eventId")).longValue()).containsExactly(80L);
        assertThat(mapper.countEvents(null,null,null,null,null)).isEqualTo(3);
        assertThat(mapper.queryExportEvents(null,null,null,null,null,null,100))
                .extracting(row -> ((Number)row.get("eventId")).longValue()).containsExactly(100L,90L,80L);
        assertThat(mapper.aggregateCommissionKinds()).hasSize(3)
                .extracting(row -> row.get("kind")).containsExactlyInAnyOrder("direct_purchase","leadership","cultivation");
        var statuses = mapper.aggregateCommissionStatuses();
        assertThat(statuses).hasSize(2);
        assertThat(statuses.stream().filter(row -> "USDT".equals(row.get("currency"))).findFirst().orElseThrow().get("amount"))
                .isEqualTo(new java.math.BigDecimal("30.000000"));
        assertThat(statuses.stream().filter(row -> "UVT".equals(row.get("currency"))).findFirst().orElseThrow().get("amount"))
                .isEqualTo(new java.math.BigDecimal("50.000000"));

        event(1, "unregistered_reward", "USDT"); event(2, null, "USDT");
        assertThat(mapper.unknownCommissionKindCount()).isEqualTo(2);
        jdbc.update("UPDATE nx_commission_event SET is_deleted=1 WHERE id IN (1,2)");
        assertThat(mapper.unknownCommissionKindCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_commission_event WHERE commission_type='leaderboard_prize' AND amount_usdt=999", Long.class)).isEqualTo(2);
    }

    private List<Map<String,Object>> rows() { return mapper.queryEvents(null,null,3778L,null,null,null,100); }
    private Object biz(long id) { return rows().stream().filter(row -> ((Number) row.get("eventId")).longValue()==id).findFirst().orElseThrow().get("ledgerBizNo"); }
    private void event(long id,String kind,String asset) { jdbc.update("INSERT INTO nx_commission_event(id,user_id,commission_type,currency) VALUES(?,3778,?,?)",id,kind,asset); }
    private void group(String no,long user,Long usdt,Long nex) { jdbc.update("INSERT INTO nx_direct_referral_settlement(settlement_no,beneficiary_user_id,usdt_event_id,nex_event_id,source_type) VALUES(?,?,?,?,COALESCE((SELECT commission_type FROM nx_commission_event WHERE id IN (?,?) LIMIT 1),'direct_purchase'))",no,user,usdt,nex,usdt,nex); }
    private void ledger(long id,String no,long user,String asset,String direction) { jdbc.update("INSERT INTO nx_wallet_ledger(id,biz_no,user_id,biz_type,asset,direction,status) VALUES(?,?,?,'TEAM_COMMISSION',?,?,'SUCCESS')",id,no,user,asset,direction); }
}
