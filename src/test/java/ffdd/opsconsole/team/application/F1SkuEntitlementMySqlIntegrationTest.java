package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository;
import ffdd.opsconsole.team.domain.VRankSkuFulfillmentRow;
import ffdd.opsconsole.team.dto.VRankRewardPayoutActionRequest;
import ffdd.opsconsole.team.infrastructure.MybatisTeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.team.mapper.TeamFulfillmentQueueMapper;
import java.util.UUID;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Actual production mapper SQL and Spring transactions in a disposable local-only schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_F1_SKU_MYSQL_IT", matches = "true")
class F1SkuEntitlementMySqlIntegrationTest {
    private JdbcTemplate admin, jdbc;
    private String schema;
    private OpsTeamService ops;
    private VRankSkuFulfillmentService worker;
    private AuditLogService audit;
    private EventOutboxService outbox;
    private final VRankSkuFulfillmentRow row = new VRankSkuFulfillmentRow(7L, 21L, "V1", "original-box", "PENDING");

    @BeforeEach
    void fixture() {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        String url = "jdbc:mysql://" + endpoint + "/?useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        schema = "nexion_f1_sku_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        System.out.println("F1_SKU_SCHEMA_CREATED " + schema);
        var source = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), "root", "");
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
        jdbc.execute("CREATE TABLE nx_product(product_no VARCHAR(64) PRIMARY KEY,stock INT,sold_count INT,inventory_mode VARCHAR(16),product_type VARCHAR(16),status VARCHAR(32),store_visible TINYINT,is_deleted TINYINT DEFAULT 0,updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_v_rank_reward_fulfillment(id BIGINT PRIMARY KEY,user_id BIGINT,rank_code VARCHAR(16),reward_name VARCHAR(128),status VARCHAR(32),reason VARCHAR(255),is_deleted TINYINT DEFAULT 0,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,fulfilled_at DATETIME,KEY target(user_id,rank_code,reward_name)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_user_sku_entitlement(id BIGINT AUTO_INCREMENT PRIMARY KEY,fulfillment_id BIGINT,user_id BIGINT,rank_code VARCHAR(16),sku_id VARCHAR(64),status VARCHAR(32),source VARCHAR(32),is_deleted TINYINT DEFAULT 0,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY fulfillment(fulfillment_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_v_rank_reward_payout(payout_id VARCHAR(128) PRIMARY KEY,user_id BIGINT,rank_code VARCHAR(16),reward_type VARCHAR(16),sku_id VARCHAR(64),voucher_id VARCHAR(80),amount DECIMAL(18,6),custom_label VARCHAR(200),sponsor_user_id BIGINT,status VARCHAR(32),commission_event_id BIGINT,bill_id VARCHAR(128),trigger_event_id VARCHAR(128),operator VARCHAR(80),reason VARCHAR(255),granted_at DATETIME DEFAULT CURRENT_TIMESTAMP,reversed_at DATETIME,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,is_deleted TINYINT DEFAULT 0,UNIQUE KEY reward(user_id,rank_code,reward_type)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE fixture_effect(kind VARCHAR(20),detail VARCHAR(128)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO nx_product(product_no,stock,sold_count,inventory_mode,product_type,status,store_visible) VALUES('original-box',2,0,'FINITE','DEVICE','ON_SALE',1)");
        jdbc.update("INSERT INTO nx_v_rank_reward_fulfillment(id,user_id,rank_code,reward_name,status) VALUES(7,21,'V1','original-box','PENDING'),(8,22,'V1','original-box','PENDING')");
        jdbc.update("INSERT INTO nx_v_rank_reward_payout(payout_id,user_id,rank_code,reward_type,sku_id,status) VALUES('pay-21',21,'V1','sku','original-box','PENDING_GRANT'),('pay-22',22,'V1','sku','original-box','PENDING_GRANT')");
        var config = new Configuration(new Environment("f1-sku-isolated", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TeamCommissionMapper.class);
        config.addMapper(TeamFulfillmentQueueMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var tx = new DataSourceTransactionManager(source);
        audit = mock(AuditLogService.class);
        outbox = mock(EventOutboxService.class);
        doAnswer(call -> { jdbc.update("INSERT INTO fixture_effect VALUES('audit',?)", ((ffdd.opsconsole.shared.audit.AuditLogWriteRequest) call.getArgument(0)).getAction()); return null; })
                .when(audit).recordRequired(any());
        doAnswer(call -> { jdbc.update("INSERT INTO fixture_effect VALUES('event',?)", call.getArgument(2).toString()); return null; })
                .when(outbox).publish(any(), any(), any(), any());
        worker = new VRankSkuFulfillmentService(session.getMapper(TeamFulfillmentQueueMapper.class), tx, audit, outbox);
        var target = new OpsTeamService(null, null, null, audit, OpsReadTimeSeedPolicy.disabledForDirectConstruction(),
                mock(TeamFulfillmentQueueRepository.class), new MybatisTeamCommissionRepository(session.getMapper(TeamCommissionMapper.class)),
                null, null, null, null, outbox, null, null, null, null, null, worker);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        ops = (OpsTeamService) proxy.getProxy();
    }

    @AfterEach
    void cleanup() {
        A2ReplayContext.exitReplay();
        if (schema != null && schema.matches("nexion_f1_sku_it_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE " + schema);
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)).isZero();
            System.out.println("F1_SKU_SCHEMA_DROPPED " + schema);
        }
    }

    private void action(String direction) {
        A2ReplayContext.enterReplay("isolated-sku-" + direction);
        try {
            var request = new VRankRewardPayoutActionRequest("isolated original SKU correction", "acceptance");
            var result = "reverse".equals(direction) ? ops.reverseRewardPayout("pay-21", "sku-reverse", request)
                    : ops.reissueRewardPayout("pay-21", "sku-reissue", request);
            assertThat(result.getCode()).isZero();
        } finally { A2ReplayContext.exitReplay(); }
    }

    private String status(String table, String key) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + key, String.class);
    }

    private void pendingCancelled() {
        assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("REVERSED");
        assertThat(status("nx_v_rank_reward_fulfillment", "id=7")).isEqualTo("CANCELLED");
        assertThat(status("nx_v_rank_reward_fulfillment", "id=8")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isEqualTo(2);
    }

    @Test
    void reversedLegacyQueuesDoNotStarveLaterEligibleSkuOrChangeLegacyRows() {
        jdbc.update("DELETE FROM nx_v_rank_reward_fulfillment");
        jdbc.update("DELETE FROM nx_v_rank_reward_payout");
        for (int id = 1; id <= 25; id++) {
            jdbc.update("INSERT INTO nx_v_rank_reward_fulfillment(id,user_id,rank_code,reward_name,status,reason,created_at,updated_at) VALUES(?,?,'V1','original-box','PENDING','legacy reverse left pending','2026-01-01','2026-01-01')", id, 100 + id);
            jdbc.update("INSERT INTO nx_v_rank_reward_payout(payout_id,user_id,rank_code,reward_type,sku_id,status,operator,reason,reversed_at) VALUES(?,?,'V1','sku','original-box','REVERSED','legacy','previous correction','2026-01-01')", "legacy-" + id, 100 + id);
        }
        jdbc.update("INSERT INTO nx_v_rank_reward_fulfillment(id,user_id,rank_code,reward_name,status,created_at) VALUES(26,126,'V1','original-box','PENDING','2026-01-02')");
        jdbc.update("INSERT INTO nx_v_rank_reward_payout(payout_id,user_id,rank_code,reward_type,sku_id,status) VALUES('eligible-26',126,'V1','sku','original-box','PENDING_GRANT')");
        var oldQueues = jdbc.queryForList("SELECT * FROM nx_v_rank_reward_fulfillment WHERE id<=25 ORDER BY id");
        var oldPayouts = jdbc.queryForList("SELECT * FROM nx_v_rank_reward_payout WHERE user_id<=125 ORDER BY user_id");

        int first = worker.processPending(25);
        int second = worker.processPending(25);
        System.out.println("F1_SKU_SCAN_TICKS first=" + first + " second=" + second
                + " target26=" + status("nx_v_rank_reward_payout", "payout_id='eligible-26'"));
        assertThat(jdbc.queryForList("SELECT * FROM nx_v_rank_reward_fulfillment WHERE id<=25 ORDER BY id")).isEqualTo(oldQueues);
        assertThat(jdbc.queryForList("SELECT * FROM nx_v_rank_reward_payout WHERE user_id<=125 ORDER BY user_id")).isEqualTo(oldPayouts);
        assertThat(new int[]{first, second}).containsExactly(1, 0);
        assertThat(status("nx_v_rank_reward_fulfillment", "id=26")).isEqualTo("FULFILLED");
        assertThat(status("nx_v_rank_reward_payout", "payout_id='eligible-26'")).isEqualTo("GRANTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement WHERE fulfillment_id=26 AND user_id=126 AND rank_code='V1' AND sku_id='original-box' AND status='GRANTED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product WHERE product_no='original-box'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sold_count FROM nx_product WHERE product_no='original-box'", Integer.class)).isEqualTo(1);

        jdbc.update("INSERT INTO nx_product(product_no,stock,sold_count,inventory_mode,product_type,status,store_visible) VALUES('next-box',1,0,'FINITE','DEVICE','ON_SALE',1)");
        jdbc.update("INSERT INTO nx_v_rank_reward_fulfillment(id,user_id,rank_code,reward_name,status,created_at) VALUES(27,127,'V1','next-box','PENDING','2026-01-03')");
        jdbc.update("INSERT INTO nx_v_rank_reward_payout(payout_id,user_id,rank_code,reward_type,sku_id,status) VALUES('eligible-27',127,'V1','sku','next-box','PENDING_GRANT')");
        assertThat(worker.processPending(25)).isEqualTo(1);
        assertThat(worker.processPending(25)).isZero();
        assertThat(status("nx_v_rank_reward_fulfillment", "id=27")).isEqualTo("FULFILLED");
        assertThat(status("nx_v_rank_reward_payout", "payout_id='eligible-27'")).isEqualTo("GRANTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product WHERE product_no='next-box'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sold_count FROM nx_product WHERE product_no='next-box'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM nx_v_rank_reward_fulfillment WHERE id<=25 ORDER BY id")).isEqualTo(oldQueues);
        assertThat(jdbc.queryForList("SELECT * FROM nx_v_rank_reward_payout WHERE user_id<=125 ORDER BY user_id")).isEqualTo(oldPayouts);
    }

    @Test
    void pendingReverseStopsOnlyOriginalQueueAndStaleWorkerDoesNothing() {
        action("reverse");
        pendingCancelled();
        assertThat(worker.processOne(row)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isZero();
    }

    @Test
    void grantedReverseReturnsStockAndReissueRestoresSameEntitlementExactlyOnce() {
        assertThat(worker.processOne(row)).isTrue();
        action("reverse");
        pendingCancelled();
        assertThat(status("nx_user_sku_entitlement", "fulfillment_id=7")).isEqualTo("REVERSED");
        action("reissue");
        assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("REISSUED");
        assertThat(status("nx_user_sku_entitlement", "fulfillment_id=7")).isEqualTo("GRANTED");
        assertThat(status("nx_v_rank_reward_fulfillment", "id=7")).isEqualTo("FULFILLED");
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> action("reissue")).isInstanceOf(AssertionError.class);
        assertThat(worker.processOne(row)).isFalse();
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingInactiveAndEmptyOriginalSkuRejectReissueAndRollback() {
        action("reverse");
        for (String mutation : new String[]{"UPDATE nx_product SET store_visible=0", "UPDATE nx_product SET store_visible=1,stock=0", "DELETE FROM nx_product"}) {
            jdbc.update(mutation);
            assertThatThrownBy(() -> action("reissue")).isInstanceOf(RuntimeException.class);
            assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("REVERSED");
            assertThat(status("nx_v_rank_reward_fulfillment", "id=7")).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isZero();
        }
    }

    @Test
    void auditFailureRollsBackReissueStockEntitlementQueueAndPayout() {
        action("reverse");
        int effects = jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class);
        doThrow(new IllegalStateException("fixture audit unavailable")).when(audit).recordRequired(any());
        assertThatThrownBy(() -> action("reissue")).isInstanceOf(IllegalStateException.class);
        pendingCancelled();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class)).isEqualTo(effects);
    }

    @Test
    void auditFailureRollsBackGrantedReverseIncludingReturnedInventory() {
        assertThat(worker.processOne(row)).isTrue();
        int effects = jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class);
        doThrow(new IllegalStateException("fixture audit unavailable")).when(audit).recordRequired(any());
        assertThatThrownBy(() -> action("reverse")).isInstanceOf(IllegalStateException.class);
        assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("GRANTED");
        assertThat(status("nx_v_rank_reward_fulfillment", "id=7")).isEqualTo("FULFILLED");
        assertThat(status("nx_user_sku_entitlement", "fulfillment_id=7")).isEqualTo("GRANTED");
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sold_count FROM nx_product", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class)).isEqualTo(effects);
    }

    @Test
    void finalPayoutOutboxFailureRollsBackAlreadyUpdatedPayoutAndAllGrantWrites() {
        action("reverse");
        int effects = jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class);
        doThrow(new IllegalStateException("fixture final outbox unavailable")).when(outbox)
                .publish(eq("VRANK_REWARD_PAYOUT"), any(), eq("VRANK_REWARD_PAYOUT_REISSUED"), any());
        assertThatThrownBy(() -> action("reissue")).isInstanceOf(IllegalStateException.class);
        pendingCancelled();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class)).isEqualTo(effects);
    }

    @Test
    void actualPayoutSqlFailureRollsBackPrecedingGrantAndAuditWrites() {
        action("reverse");
        int effects = jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class);
        jdbc.execute("CREATE TRIGGER reject_reissue BEFORE UPDATE ON nx_v_rank_reward_payout FOR EACH ROW BEGIN IF NEW.status='REISSUED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture payout failure'; END IF; END");
        assertThatThrownBy(() -> action("reissue")).isInstanceOf(RuntimeException.class).hasStackTraceContaining("fixture payout failure");
        pendingCancelled();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class)).isEqualTo(effects);
    }

    @Test
    void workerFailureRollsBackGrantAndLeavesOnlyRetryableQueueWhichCanBeCancelled() {
        doThrow(new IllegalStateException("fixture grant outbox unavailable")).when(outbox)
                .publish(eq("SKU_ENTITLEMENT"), any(), eq("sku.entitlement.granted"), any());
        assertThat(worker.processOne(row)).isFalse();
        assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("PENDING_GRANT");
        assertThat(status("nx_v_rank_reward_fulfillment", "id=7")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sold_count FROM nx_product", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class)).isZero();
        action("reverse");
        pendingCancelled();
        assertThat(worker.processOne(row)).isFalse();
    }

    @Test
    void mismatchedEntitlementRejectsReverseAndRollsBackStockReturn() {
        assertThat(worker.processOne(row)).isTrue();
        jdbc.update("UPDATE nx_user_sku_entitlement SET user_id=22 WHERE fulfillment_id=7");
        assertThatThrownBy(() -> action("reverse")).isInstanceOf(RuntimeException.class).hasMessage("SKU_ENTITLEMENT_READBACK_FAILED");
        assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("GRANTED");
        assertThat(status("nx_v_rank_reward_fulfillment", "id=7")).isEqualTo("FULFILLED");
        assertThat(status("nx_user_sku_entitlement", "fulfillment_id=7")).isEqualTo("GRANTED");
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isEqualTo(1);
    }

    @Test
    void unlimitedShareReturnsNoFiniteStockAndOffSaleGrantedSkuCanStillBeRevoked() {
        jdbc.update("UPDATE nx_product SET inventory_mode='UNLIMITED',product_type='SHARE',stock=0");
        assertThat(worker.processOne(row)).isTrue();
        jdbc.update("UPDATE nx_product SET store_visible=0,status='OFF_SALE'");
        action("reverse");
        assertThat(status("nx_user_sku_entitlement", "fulfillment_id=7")).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sold_count FROM nx_product", Integer.class)).isZero();
        jdbc.update("UPDATE nx_product SET store_visible=1,status='ON_SALE'");
        action("reissue");
        assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("REISSUED");
        assertThat(status("nx_user_sku_entitlement", "fulfillment_id=7")).isEqualTo("GRANTED");
        assertThat(jdbc.queryForObject("SELECT stock FROM nx_product", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sold_count FROM nx_product", Integer.class)).isEqualTo(1);
    }

    @Test
    void reverseWinningPayoutLockPreventsWaitingWorkerFromGranting() throws Exception {
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { inside.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); return null; })
                .when(audit).recordRequired(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var reverse = pool.submit(() -> action("reverse"));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            var workerStarted = new CountDownLatch(1);
            var grant = pool.submit(() -> { workerStarted.countDown(); return worker.processOne(row); });
            assertThat(workerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> grant.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            reverse.get(10, TimeUnit.SECONDS);
            assertThat(grant.get(10, TimeUnit.SECONDS)).isFalse();
            pendingCancelled();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_sku_entitlement", Integer.class)).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void duplicateQueueAssociationRejectsWithoutBroadCancellation() {
        jdbc.update("INSERT INTO nx_v_rank_reward_fulfillment(id,user_id,rank_code,reward_name,status) VALUES(9,21,'V1','original-box','PENDING')");
        assertThatThrownBy(() -> action("reverse")).isInstanceOf(RuntimeException.class);
        assertThat(status("nx_v_rank_reward_payout", "payout_id='pay-21'")).isEqualTo("PENDING_GRANT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_v_rank_reward_fulfillment WHERE status='PENDING'", Integer.class)).isEqualTo(3);
    }

    @Test
    void workerAndReverseSerializeAtOriginalPayoutAndLeaveNoGrantedRevokedRight() throws Exception {
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { inside.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); return null; })
                .when(audit).recordRequired(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var grant = pool.submit(() -> worker.processOne(row));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            var reverseStarted = new CountDownLatch(1);
            var reverse = pool.submit(() -> { reverseStarted.countDown(); action("reverse"); });
            assertThat(reverseStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> reverse.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(grant.get(10, TimeUnit.SECONDS)).isTrue();
            reverse.get(10, TimeUnit.SECONDS);
            pendingCancelled();
            assertThat(status("nx_user_sku_entitlement", "fulfillment_id=7")).isEqualTo("REVERSED");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
