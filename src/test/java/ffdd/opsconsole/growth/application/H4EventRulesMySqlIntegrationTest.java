package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import ffdd.opsconsole.growth.infrastructure.H4EventSqlConfiguration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real MySQL schema, trigger, row locks, and DB-backed money doubles; not production wallet E2E. */
@EnabledIfEnvironmentVariable(named="H4_EVENT_JDBC_URL",matches=".+")
class H4EventRulesMySqlIntegrationTest {
    JdbcTemplate jdbc;H4EventRuleRepository db;H4EventRuleService policies;H4EventEngine engine;TransactionTemplate tx;
    EarningsReleaseService earnings;
    TreasuryLedgerPostingFacade ledger;
    @BeforeEach void prepare() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("H4_EVENT_JDBC_URL"),System.getenv("H4_EVENT_DB_USER"),System.getenv("H4_EVENT_DB_PASSWORD"));
        jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).startsWith("h4_events_");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox INT,is_deleted INT,country_code VARCHAR(8),region VARCHAR(32),created_at DATETIME,language VARCHAR(16),v_rank VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_user_wallet(user_id BIGINT PRIMARY KEY,nex_available DECIMAL(18,6),is_deleted INT,sandbox INT NOT NULL DEFAULT 0)");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_wallet' AND column_name='sandbox'",Integer.class)==0)
            jdbc.execute("ALTER TABLE nx_user_wallet ADD sandbox INT NOT NULL DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_user_level_log(user_id BIGINT,level_type VARCHAR(16),to_code VARCHAR(16),created_at DATETIME,is_deleted INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_order(order_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,amount_usdt DECIMAL(18,6),payment_status VARCHAR(24),order_status VARCHAR(24),paid_at DATETIME(6),is_deleted INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_promotion_refund_hold(order_no VARCHAR(96),status VARCHAR(24))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS h4_test_credit(receipt VARCHAR(160) PRIMARY KEY,user_id BIGINT,amount DECIMAL(18,6))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS h4_test_ledger(receipt VARCHAR(160) PRIMARY KEY,user_id BIGINT,amount DECIMAL(18,6))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_event_quest(id BIGINT AUTO_INCREMENT PRIMARY KEY,quest_code VARCHAR(64) UNIQUE,quest_name VARCHAR(128),description VARCHAR(512),target_type VARCHAR(32),target_value INT,reward_type VARCHAR(32),reward_amount DECIMAL(18,6),reward_name VARCHAR(128),cta_href VARCHAR(255),status INT,sort_order INT)");
        for(String column:List.of("is_deleted","updated_at"))if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_quest' AND column_name=?",Integer.class,column)==0)
            jdbc.execute("ALTER TABLE nx_event_quest ADD "+column+(column.equals("is_deleted")?" INT NOT NULL DEFAULT 0":" DATETIME NULL"));
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_config_item(config_key VARCHAR(128) PRIMARY KEY,config_value TEXT,status INT,is_deleted INT)");
        String migration=Files.readString(Path.of("scripts/migrations/20261025_h4_event_rules.sql"));
        // Keep the MySQL trigger body as one statement; ScriptUtils handles ordinary DDL/DML.
        int delimiter=migration.indexOf("DELIMITER $$");
        try(var connection=ds.getConnection()){
            ScriptUtils.executeSqlScript(connection,new ByteArrayResource(migration.substring(0,delimiter).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String trigger=migration.substring(delimiter+"DELIMITER $$".length(),migration.indexOf("END$$")+3).trim();
            jdbc.execute("DROP TRIGGER IF EXISTS nx_wallet_h4_holding_observation");
            jdbc.execute(trigger);
        }
        for(String table:List.of("h4_test_credit","h4_test_ledger","nx_growth_event_pool_result","nx_growth_event_holding","nx_growth_event_claim","nx_growth_event_region","nx_growth_event_reminder","nx_order","nx_promotion_refund_hold","nx_user_wallet","nx_user_level_log","nx_user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE nx_growth_event_rule SET enabled=0,activated_at=NULL,disabled_at=NULL,settled_at=NULL,settlement_hash=NULL,spent_nex=0,spent_usdt=0,spent_devices=0,spent_tickets=0");
        db=new H4EventRuleRepository(new H4EventSqlConfiguration().h4EventRawMapper(ds));var commerce=mock(H4EventCommerceService.class);var coverage=mock(TreasuryCoverageFacade.class);var audit=mock(AuditLogService.class);
        when(coverage.snapshot()).thenReturn(new ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot(new BigDecimal("200"),new BigDecimal("100")));
        policies=new H4EventRuleService(db,new ObjectMapper(),audit,commerce,coverage,mock(A2RuntimePolicy.class));
        earnings=mock(EarningsReleaseService.class);
        ledger=mock(TreasuryLedgerPostingFacade.class);
        when(earnings.creditReward(anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString())).thenAnswer(call->{
            jdbc.update("INSERT INTO h4_test_credit VALUES(?,?,?)",call.getArgument(5),call.getArgument(0),call.getArgument(4));return call.getArgument(5);
        });
        accountingSucceeds();
        engine=new H4EventEngine(db,policies,commerce,mock(AppGrowthEngagementMapper.class),earnings,ledger,coverage,mock(AdminIdempotencyService.class),audit);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Test void closingHoldingKeepsExistingObserversAndRejectsNewParticipants(){
        jdbc.update("INSERT INTO nx_user VALUES(1,'ACTIVE',0,0,'+84',NULL,NOW(),'vi','V1')");
        jdbc.update("INSERT INTO nx_user_wallet(user_id,nex_available,is_deleted) VALUES(1,1500,0)");
        jdbc.update("UPDATE nx_growth_event_rule SET enabled=1,activated_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 8 DAY),starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 8 DAY),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),rules_json=JSON_OBJECT('minNex',1000,'holdDays',7,'poolUsdt',5000,'allocation','EQUAL') WHERE event_code='evt-nex-holders-share'");
        jdbc.update("INSERT INTO nx_growth_event_holding VALUES('evt-nex-holders-share',1,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 6 DAY),NULL,UTC_TIMESTAMP(6))");
        Timestamp before=jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class);
        jdbc.update("UPDATE nx_user_wallet SET nex_available=1400 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isEqualTo(before);
        jdbc.update("UPDATE nx_user_wallet SET nex_available=999 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNull();
        jdbc.update("UPDATE nx_user_wallet SET nex_available=1500 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,eligible_since,UTC_TIMESTAMP(6)) FROM nx_growth_event_holding",Long.class)).isBetween(0L,5L);
        tx.executeWithoutResult(s->policies.stop("evt-nex-holders-share"));
        jdbc.update("UPDATE nx_user_wallet SET nex_available=900 WHERE user_id=1");jdbc.update("UPDATE nx_user_wallet SET nex_available=1500 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNotNull();
        jdbc.update("INSERT INTO nx_user VALUES(2,'ACTIVE',0,0,'+84',NULL,NOW(),'vi','V1')");
        jdbc.update("INSERT INTO nx_user_wallet(user_id,nex_available,is_deleted) VALUES(2,0,0)");
        jdbc.update("UPDATE nx_user_wallet SET nex_available=1500 WHERE user_id=2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_holding WHERE user_id=2",Integer.class)).isZero();
    }
    void accountingSucceeds(){
        doAnswer(call->{jdbc.update("INSERT INTO h4_test_ledger VALUES(?,?,?)",call.getArgument(0),call.getArgument(1),call.getArgument(5));return null;})
            .when(ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString(),anyString());
    }
    void regionalFixture(){
        jdbc.update("UPDATE nx_growth_event_rule SET enabled=1,activated_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 DAY),starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 DAY),ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),rules_json=JSON_OBJECT('metric','PAID_ORDER_USDT','allocation','PROPORTIONAL_VOLUME','poolUsdt',1,'regions',JSON_ARRAY(JSON_OBJECT('name','SEA','countries',JSON_ARRAY('VN')),JSON_OBJECT('name','OTHER','countries',JSON_ARRAY('CN')))) WHERE event_code='evt-regional-pk'");
        for(int id=1;id<=3;id++){
            jdbc.update("INSERT INTO nx_user VALUES(?,'ACTIVE',0,0,'+84',NULL,NOW(),'en','V1')",id);
            jdbc.update("INSERT INTO nx_growth_event_region VALUES('evt-regional-pk',?,?,'VN',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY))",id,id<3?"SEA":"OTHER");
            jdbc.update("INSERT INTO nx_order VALUES(?,?,?,'PAID','ACTIVE',DATE_SUB(NOW(6),INTERVAL 1 HOUR),0)","o"+id,id,id==1?10:id==2?20:29);
        }
        jdbc.update("INSERT INTO nx_order VALUES('refunded',3,999,'PAID','REFUNDED',DATE_SUB(NOW(6),INTERVAL 1 HOUR),0)");
    }
    @Test void concurrentSettlementsFreezeOneExactPoolAndIgnoreRefundedOrders() throws Exception {
        regionalFixture();
        var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try{
            List<Future<?>> calls=new ArrayList<>();
            for(int i=0;i<2;i++)calls.add(workers.submit(()->{start.await();tx.executeWithoutResult(s->engine.settle("evt-regional-pk"));return null;}));
            start.countDown();for(var call:calls)call.get(20,TimeUnit.SECONDS);
        }finally{workers.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_pool_result",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_pool_result WHERE status='PAID'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(reward_usdt) FROM nx_growth_event_pool_result",BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT reward_usdt FROM nx_growth_event_pool_result WHERE user_id=2",BigDecimal.class)).isEqualByComparingTo("0.666667");
        tx.executeWithoutResult(s->engine.settle("evt-regional-pk"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_pool_result",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM h4_test_credit",BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM h4_test_ledger",BigDecimal.class)).isEqualByComparingTo("1");
    }
    @Test void wholePoolRollsBackOnSecondLedgerFailureAndSettlesAfterClosingExpiredEvent(){
        regionalFixture();
        jdbc.update("UPDATE nx_growth_event_rule SET activated_at=starts_at WHERE event_code='evt-regional-pk'");
        tx.executeWithoutResult(s->policies.stop("evt-regional-pk"));
        doAnswer(call->{if(((Long)call.getArgument(1))==2L)throw new IllegalStateException("test ledger unavailable");
            jdbc.update("INSERT INTO h4_test_ledger VALUES(?,?,?)",call.getArgument(0),call.getArgument(1),call.getArgument(5));return null;})
            .when(ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString(),anyString());
        assertThatThrownBy(()->tx.executeWithoutResult(s->engine.settle("evt-regional-pk"))).isInstanceOf(IllegalStateException.class);
        for(String table:List.of("h4_test_credit","h4_test_ledger","nx_growth_event_pool_result","nx_growth_event_claim"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT settled_at FROM nx_growth_event_rule WHERE event_code='evt-regional-pk'",Timestamp.class)).isNull();
        accountingSucceeds();tx.executeWithoutResult(s->engine.settle("evt-regional-pk"));
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM h4_test_credit",BigDecimal.class)).isEqualByComparingTo("1");
        jdbc.update("UPDATE nx_order SET order_status='REFUNDED' WHERE order_no='o1'");
        tx.executeWithoutResult(s->engine.settle("evt-regional-pk"));
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM h4_test_credit",BigDecimal.class)).isEqualByComparingTo("1");
    }
    @Test void settlementWaitsForAnInFlightRefundAndExcludesItAfterCommit() throws Exception {
        regionalFixture();var refundLocked=new CountDownLatch(1);var releaseRefund=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var refund=workers.submit(()->tx.executeWithoutResult(s->{
                jdbc.queryForList("SELECT order_no FROM nx_order WHERE order_no='o1' FOR UPDATE");
                jdbc.update("UPDATE nx_order SET order_status='REFUNDED' WHERE order_no='o1'");refundLocked.countDown();
                try{assertThat(releaseRefund.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
            }));
            assertThat(refundLocked.await(10,TimeUnit.SECONDS)).isTrue();
            var settlement=workers.submit(()->tx.executeWithoutResult(s->engine.settle("evt-regional-pk")));
            assertThatThrownBy(()->settlement.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseRefund.countDown();refund.get(10,TimeUnit.SECONDS);settlement.get(10,TimeUnit.SECONDS);
        }finally{releaseRefund.countDown();workers.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT user_id FROM nx_growth_event_pool_result",Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM h4_test_credit",BigDecimal.class)).isEqualByComparingTo("1");
    }
    @Test void closedOnboardingKeepsOnlyTheRankEarnedBeforeItsCutoff(){
        jdbc.update("UPDATE nx_growth_event_rule SET enabled=0,activated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 4 DAY),starts_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 4 DAY),disabled_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY),ends_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 3 DAY),rules_json=JSON_OBJECT('windowDays',7,'minRank',2,'rewardNex',200,'streakSavers',1,'budgetNex',2000) WHERE event_code='evt-onboarding-7d'");
        for(int id=1;id<=2;id++)jdbc.update("INSERT INTO nx_user VALUES(?,'ACTIVE',0,0,'+84',NULL,DATE_SUB(NOW(),INTERVAL 3 DAY),'en','V2')",id);
        jdbc.update("INSERT INTO nx_user_level_log VALUES(1,'VRANK','V2',DATE_SUB(NOW(),INTERVAL 2 DAY),0),(2,'VRANK','V2',NOW(),0)");
        var source=List.of(H4EventRuleService.values("eventCode","evt-onboarding-7d","userStatus","AVAILABLE"));
        assertThat(engine.project(1L,source,"en")).singleElement().satisfies(row->assertThat(row.get("userStatus")).isEqualTo("CLAIMABLE"));
        assertThat(engine.project(2L,source,"en")).isEmpty();
    }
    void holdingAccount(){
        jdbc.update("INSERT INTO nx_user VALUES(1,'ACTIVE',0,0,'+84',NULL,NOW(),'en','V1')");
        jdbc.update("INSERT INTO nx_user_wallet(user_id,nex_available,is_deleted) VALUES(1,1500,0)");
    }
    void await(CountDownLatch latch){
        try{assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
    }
    @Test void activationWaitsForAnUncommittedWalletDebitBeforeTakingItsBaseline() throws Exception {
        holdingAccount();
        jdbc.update("INSERT INTO nx_config_item VALUES('growth.content.localized',?,1,0) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",
            "{\"event\":{\"evt-nex-holders-share\":{\"zh\":{\"name\":\"持币\",\"description\":\"规则\",\"rewardName\":\"奖池\"},\"vi\":{\"name\":\"Event\",\"description\":\"Rules\",\"rewardName\":\"Pool\"}}}}");
        var revision=jdbc.queryForObject("SELECT revision FROM nx_growth_event_rule WHERE event_code='evt-nex-holders-share'",Long.class);
        var request=new ffdd.opsconsole.growth.dto.H4EventRuleRequest(revision,true,java.time.Instant.now().minusSeconds(86400).toString(),java.time.Instant.now().plusSeconds(864000).toString(),Map.of("minNex",1000,"holdDays",1,"poolUsdt",10,"allocation","EQUAL"),"Start isolated holding test");
        var debitReady=new CountDownLatch(1);var release=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var debit=workers.submit(()->tx.executeWithoutResult(s->{jdbc.update("UPDATE nx_user_wallet SET nex_available=999 WHERE user_id=1");debitReady.countDown();await(release);}));
            await(debitReady);
            var activation=workers.submit(()->tx.execute(s->policies.update("evt-nex-holders-share",request)));
            assertThatThrownBy(()->activation.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();debit.get(10,TimeUnit.SECONDS);
            assertThat(activation.get(10,TimeUnit.SECONDS).getCode()).isZero();
        } finally {release.countDown();workers.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_holding WHERE eligible_since IS NOT NULL",Integer.class)).isZero();
        jdbc.update("UPDATE nx_user_wallet SET nex_available=1500 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_holding WHERE eligible_since IS NOT NULL",Integer.class)).isEqualTo(1);
    }
    @Test void closingWaitsForAnInFlightObserverAndKeepsItsEarnedMembership() throws Exception {
        holdingAccount();
        jdbc.update("UPDATE nx_growth_event_rule SET enabled=1,activated_at=UTC_TIMESTAMP(6),starts_at=UTC_TIMESTAMP(6),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 DAY),rules_json=JSON_OBJECT('minNex',1000,'holdDays',1,'poolUsdt',10,'allocation','EQUAL') WHERE event_code='evt-nex-holders-share'");
        var updateReady=new CountDownLatch(1);var release=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var update=workers.submit(()->tx.executeWithoutResult(s->{jdbc.update("UPDATE nx_user_wallet SET nex_available=1600 WHERE user_id=1");updateReady.countDown();await(release);}));
            await(updateReady);
            var closing=workers.submit(()->tx.executeWithoutResult(s->policies.stop("evt-nex-holders-share")));
            assertThatThrownBy(()->closing.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();update.get(10,TimeUnit.SECONDS);closing.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();workers.shutdownNow();}
        jdbc.update("UPDATE nx_user_wallet SET nex_available=900 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNull();
        jdbc.update("UPDATE nx_user_wallet SET nex_available=1500 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNotNull();
    }
    @Test void cutoffSettlementWaitsForTheWalletDebitAndCannotPayItsOldObservation() throws Exception {
        holdingAccount();
        jdbc.update("UPDATE nx_growth_event_rule SET enabled=1,activated_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 3 DAY),starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 3 DAY),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 3 SECOND),rules_json=JSON_OBJECT('minNex',1000,'holdDays',1,'poolUsdt',10,'allocation','EQUAL') WHERE event_code='evt-nex-holders-share'");
        jdbc.update("INSERT INTO nx_growth_event_holding VALUES('evt-nex-holders-share',1,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 DAY),NULL,UTC_TIMESTAMP(6))");
        var debitReady=new CountDownLatch(1);var release=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var debit=workers.submit(()->tx.executeWithoutResult(s->{
                jdbc.update("UPDATE nx_user_wallet SET nex_available=999 WHERE user_id=1");
                assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNull();
                debitReady.countDown();await(release);
            }));
            await(debitReady);
            jdbc.queryForObject("SELECT SLEEP(GREATEST(0,TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),ends_at)/1000000.0)+0.05) FROM nx_growth_event_rule WHERE event_code='evt-nex-holders-share'",Integer.class);
            var settlement=workers.submit(()->tx.executeWithoutResult(s->engine.settle("evt-nex-holders-share")));
            assertThatThrownBy(()->settlement.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();debit.get(10,TimeUnit.SECONDS);settlement.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();workers.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_pool_result",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM h4_test_credit",Integer.class)).isZero();
    }
    @Test void unrelatedActivityRuleLockDoesNotBlockAWalletUpdate() throws Exception {
        holdingAccount();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var unrelated=workers.submit(()->tx.executeWithoutResult(s->{policies.find("evt-regional-pk",true);locked.countDown();await(release);}));
            await(locked);
            workers.submit(()->tx.executeWithoutResult(s->jdbc.update("UPDATE nx_user_wallet SET nex_available=1600 WHERE user_id=1"))).get(3,TimeUnit.SECONDS);
            release.countDown();unrelated.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test void walletEnvironmentOrDeletionChangesCannotPreserveAProductionHoldingPeriod(){
        holdingAccount();
        jdbc.update("UPDATE nx_growth_event_rule SET enabled=1,activated_at=UTC_TIMESTAMP(6),starts_at=UTC_TIMESTAMP(6),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 DAY),rules_json=JSON_OBJECT('minNex',1000,'holdDays',1,'poolUsdt',10,'allocation','EQUAL') WHERE event_code='evt-nex-holders-share'");
        jdbc.update("UPDATE nx_user_wallet SET nex_available=1600 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNotNull();
        jdbc.update("UPDATE nx_user_wallet SET sandbox=1 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNull();
        jdbc.update("UPDATE nx_user_wallet SET sandbox=0 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNotNull();
        jdbc.update("UPDATE nx_user_wallet SET is_deleted=1 WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT eligible_since FROM nx_growth_event_holding",Timestamp.class)).isNull();
    }
}
