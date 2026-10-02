package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.TestComputeWorkerService.*;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Opt-in, identity-pinned Root-owned localhost13306. Never uses a runtime datasource or shared credentials. */
@EnabledIfEnvironmentVariable(named="UVEL_BUG355_MYSQL_IT", matches="true")
class TestComputeWorkerSettlementSpringTransactionMySqlIntegrationTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:13306/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    private static final Path INSTANCE = Path.of("D:/workspace/audit-artifacts/uvel-full-test-20261001/bug355-test-mysql-r01/instance.json");
    private static final String EXPECTED_UUID = "ad1eb25e-be16-11f1-819a-26563d2f347a";
    private static final String EXPECTED_DIR = "D:/workspace/audit-artifacts/uvel-full-test-20261001/bug355-test-mysql-r01/data/";
    private static final Pattern OWN = Pattern.compile("uvel_bug355_[a-f0-9]{32}");
    private static final Instant INSTANT = TestComputeWorkerServiceTest.INSTANT;
    private static final LocalDateTime NOW = TestComputeWorkerServiceTest.NOW;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private Connection admin;
    private String schema;
    private boolean created;
    private DataSource source;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate session;
    private MockEnvironment env;
    private AppTaskAssignmentMapper mapper;
    private TestComputeWorkerService worker;
    private AppTaskAssignmentService app;
    private Grant grant;
    private String failure;
    private int outboxCalls;
    private final ControlledClock businessClock = new ControlledClock();
    private String advancePoint;
    private String deadlineKind;
    private boolean timeAdvanced;
    private int timeOutboxCalls;
    private int receiptsAfterAdvance;
    private int runtimeMarksAfterAdvance;
    private Map<String,Object> stateAtAdvance;

    @BeforeEach void fixture() throws Exception {
        // All server and manifest identity checks precede CREATE DATABASE. URL is constant, not supplied by env.
        var manifest = json.readTree(Files.readString(INSTANCE));
        assertThat(manifest.get("state").textValue()).isEqualTo("ready-verified");
        assertThat(manifest.get("host").textValue()).isEqualTo("127.0.0.1");
        assertThat(manifest.get("port").intValue()).isEqualTo(13306);
        assertThat(manifest.at("/identity/uuid").textValue()).isEqualTo(EXPECTED_UUID);
        assertThat(manifest.at("/identity/mysqlx").textValue()).isEqualTo("DISABLED");
        admin = DriverManager.getConnection(URL, "root", "");
        try (var statement=admin.createStatement(); var rows=statement.executeQuery("SELECT @@port,@@datadir,@@server_uuid,@@version, DATABASE()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(13306);
            assertThat(rows.getString(2).replace('\\','/')).isEqualTo(EXPECTED_DIR);
            assertThat(rows.getString(3)).isEqualTo(EXPECTED_UUID);
            assertThat(rows.getString(4)).isEqualTo(manifest.at("/identity/version").textValue());
            assertThat(rows.getString(5)).isNull();
        }
        try(var statement=admin.createStatement(); var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.PLUGINS WHERE PLUGIN_NAME='mysqlx' AND PLUGIN_STATUS='ACTIVE'")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
        }
        System.out.println("BUG355_LOCAL_MYSQL_PIN_OK port=13306 uuid="+EXPECTED_UUID+" datadir="+EXPECTED_DIR);
        schema="uvel_bug355_"+UUID.randomUUID().toString().replace("-", "");
        assertThat(OWN.matcher(schema).matches()).isTrue();
        try(var statement=admin.createStatement()) { statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        created=true;
        source=new DriverManagerDataSource(URL.replace("13306/", "13306/"+schema), "root", "");
        jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(schema);
        createTables(); seed(); configure("CTA-TEST-ONE");
    }

    @AfterEach void discardOnlyOwnSchema() throws Exception {
        try {
            if (created && schema!=null && OWN.matcher(schema).matches() && admin!=null) {
                try(var statement=admin.createStatement()) { statement.execute("DROP DATABASE `"+schema+"`"); }
                try(var statement=admin.prepareStatement("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")) {
                    statement.setString(1,schema);
                    try(var result=statement.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); }
                }
                System.out.println("BUG355_OWN_SCHEMA_REMOVED "+schema);
            }
        } finally { if(admin!=null) admin.close(); }
    }

    @Test void realClaimArtifactRecomputationSharedDualCurrencySettlementAndDurableReplay() throws Exception {
        var claim=claim();
        assertThat(claim.getData()).containsEntry("executionKind",TestComputeWorkerService.KIND);
        assertThat(jdbc.queryForObject("SELECT model_name FROM nx_compute_task",String.class)).isEqualTo(TestComputeWorkerService.KIND);
        assertBalances("10", "20");
        var request=result();
        var completed=app.testWorkerComplete(grant,grant.taskNo(),"complete-one",request);
        assertThat(completed.getCode()).isZero();
        assertThat(completed.getData()).containsEntry("executionKind",TestComputeWorkerService.KIND)
                .containsEntry("inputHash",request.inputHash()).containsEntry("resultHash",request.resultHash());
        assertBalances("10.25", "23");
        assertThat(count("nx_compute_receipt")).isEqualTo(1); assertThat(count("nx_wallet_ledger")).isEqualTo(2);
        assertThat(count("nx_earning_event")).isEqualTo(2); assertThat(count("nx_event_outbox")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT proof_consumed_at IS NOT NULL FROM nx_compute_task",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime",String.class)).isNull();
        String detail=jdbc.queryForObject("SELECT detail_json FROM nx_audit_log WHERE action='TASK_ASSIGNMENT_COMPLETED'",String.class);
        var parsed=json.readTree(detail);
        assertThat(Base64.getDecoder().decode(parsed.get("resultBytesBase64").textValue())).containsExactly(Base64.getDecoder().decode(request.resultArtifactBase64()));
        assertThat(parsed.get("proofHash").textValue()).isEqualTo(jdbc.queryForObject("SELECT proof_hash FROM nx_compute_receipt",String.class));
        assertThat(detail).doesNotContain("truncated","serialization", TestComputeWorkerServiceTest.TOKEN,
                env.getProperty("nexion.compute-task.test-worker.credential-sha256"));
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_type,actor_username FROM nx_audit_log"))
                .allSatisfy(row -> { assertThat(row.get("actor_type")).isEqualTo("TEST_COMPUTE_WORKER"); assertThat(row.get("actor_username")).isEqualTo(grant.getName()); });
        // New service/transaction wrappers replay the persisted envelope; no process-local cache or first-key state.
        configure(grant.taskNo());
        var replay=app.testWorkerComplete(grant,grant.taskNo(),"complete-one",request);
        assertThat(replay.getData()).containsEntry("receiptNo",completed.getData().get("receiptNo"));
        assertBalances("10.25","23");
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"complete-other",request)).hasMessage("TASK_ASSIGNMENT_PROOF_REPLAYED");
        var changed=new CompleteRequest(request.specVersion(), request.inputHash(),"b".repeat(64),request.resultArtifactBase64(),request.proofNonce(),request.proofTimestamp());
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"complete-one",changed)).hasMessageContaining("IDEMPOTENCY");
        assertThat(count("nx_wallet_ledger")).isEqualTo(2);
    }

    @Test void expiredOldLeaseCreatesOnlyTheDeterministicNewServerTaskWithFreshNonceAndOriginalFrozenReward() {
        jdbc.update("UPDATE nx_compute_task SET task_no='CTA-OLD',lease_expires_at=?,proof_expires_at=?",NOW.minusSeconds(1),NOW.minusSeconds(1));
        jdbc.update("UPDATE nx_user_device_runtime SET active_task_no='CTA-OLD'");
        String newTask=worker.newTaskNo(grant); configure(newTask);
        var response=claim();
        assertThat(response.getData().get("taskNo")).isEqualTo(newTask);
        assertThat(response.getData().get("proofNonce")).isNotEqualTo(TestComputeWorkerServiceTest.NONCE);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no='CTA-OLD'",String.class)).isEqualTo("EXPIRED");
        assertThat(count("nx_compute_task")).isEqualTo(2);
        claim(); assertThat(count("nx_compute_task")).isEqualTo(2);
        assertThatThrownBy(() -> app.testWorkerClaim(grant,newTask,"different-key")).hasMessage("TEST_COMPUTE_WORKER_IDEMPOTENCY_INVALID");
        assertBalances("10","20");
        for(String table:List.of("nx_compute_receipt","nx_wallet_ledger","nx_earning_event")) assertThat(count(table)).isZero();
    }

    @Test void concurrentDifferentKeysCannotSettleTheSameNonceTwiceInRealTransactions() throws Exception {
        claim(); var request=result();
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results=new ArrayList<>();
            for(int i=0;i<2;i++) {
                String key="complete-concurrent-"+i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if(!start.await(5,TimeUnit.SECONDS)) throw new AssertionError("local concurrent start timeout");
                    try { return app.testWorkerComplete(grant,grant.taskNo(),key,request); }
                    catch(RuntimeException failure) { return failure; }
                }));
            }
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Object> outcomes=new ArrayList<>();
            for(var future:results) outcomes.add(future.get(15,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(ffdd.opsconsole.shared.api.ApiResult.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(RuntimeException.class::isInstance).count()).isEqualTo(1);
            assertBalances("10.25","23");
            assertThat(count("nx_compute_receipt")).isEqualTo(1); assertThat(count("nx_wallet_ledger")).isEqualTo(2);
            assertThat(count("nx_earning_event")).isEqualTo(2); assertThat(count("nx_event_outbox")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='TASK_ASSIGNMENT_COMPLETED'",Integer.class)).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }

    static Stream<Arguments> completionDeadlineCases() {
        return Stream.of("lockProductionUser","lockDailyCloudShareNex","insertCloudShareNexEvent","lastOutbox")
                .flatMap(point -> Stream.of("proofOnly","leaseOnly","equality").map(kind -> Arguments.of(point,kind)));
    }

    @ParameterizedTest @MethodSource("completionDeadlineCases")
    void freshTaskDeadlineFencesActualOwnerAndSettlementSqlEvenWithLiveGrant(String point,String kind) {
        claim(); setNearDeadline(kind); var request=result(); armTimeAdvance(point,kind);
        Object outcome;
        try { outcome=app.testWorkerComplete(grant,grant.taskNo(),"deadline-"+point+"-"+kind,request); }
        catch(RuntimeException rejected) { outcome=rejected; }
        printTimeOutcome("complete",outcome);
        assertThat(timeAdvanced).isTrue();
        assertThat(businessClock.millis()).isLessThan(grant.expiresAt());
        if(point.equals("insertCloudShareNexEvent")) assertThat(stateAtAdvance).containsEntry("receipt",1).containsEntry("ledger",2);
        if(point.equals("lastOutbox")) assertThat(stateAtAdvance).containsEntry("outbox",2).containsEntry("task","COMPLETED");
        assertThat(outcome).isInstanceOf(RuntimeException.class);
        assertNoRewards();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT proof_consumed_at FROM nx_compute_task",LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("ONLINE");
        if(point.equals("lockProductionUser") || point.equals("lockDailyCloudShareNex")) assertThat(receiptsAfterAdvance).isZero();
    }

    static Stream<Arguments> claimDeadlineCases() {
        return Stream.of("lockProductionUser","markTestWorkerOnline","markTestWorkerTask")
                .flatMap(point -> Stream.of("proofOnly","leaseOnly","equality").map(kind -> Arguments.of(point,kind)));
    }

    @ParameterizedTest @MethodSource("claimDeadlineCases")
    void claimDoesNotMarkOrReturnExpiredTaskAfterActualSqlBoundaries(String point,String kind) {
        setNearDeadline(kind); armTimeAdvance(point,kind);
        Object outcome;
        try { outcome=claim(); } catch(RuntimeException rejected) { outcome=rejected; }
        printTimeOutcome("claim",outcome);
        assertThat(timeAdvanced).isTrue();
        assertThat(businessClock.millis()).isLessThan(grant.expiresAt());
        assertThat(outcome).isInstanceOf(RuntimeException.class);
        assertNoRewards();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT model_name FROM nx_compute_task",String.class)).isEqualTo("BGE-M3");
        if(point.equals("lockProductionUser")) assertThat(runtimeMarksAfterAdvance).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"lockProductionUser","lockDailyCloudShareNex","insertCloudShareNexEvent","lastOutbox"})
    void futureDeadlineControlStillSettlesExactlyOnceAfterTimeAdvances(String point) {
        claim(); var request=result(); armTimeAdvance(point,"future");
        assertThat(app.testWorkerComplete(grant,grant.taskNo(),"future-"+point,request).getCode()).isZero();
        assertThat(timeAdvanced).isTrue(); assertBalances("10.25","23");
        assertThat(count("nx_compute_receipt")).isEqualTo(1); assertThat(count("nx_wallet_ledger")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"lockProductionUser","markTestWorkerOnline","markTestWorkerTask"})
    void futureDeadlineClaimControlStillReturnsCurrentTask(String point) {
        armTimeAdvance(point,"future"); assertThat(claim().getCode()).isZero();
        assertThat(timeAdvanced).isTrue(); assertNoRewards();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("SELECT model_name FROM nx_compute_task",String.class)).isEqualTo(TestComputeWorkerService.KIND);
    }

    private void setNearDeadline(String kind) {
        jdbc.update("UPDATE nx_compute_task SET lease_expires_at=?,proof_expires_at=?",
                kind.equals("proofOnly") ? NOW.plusHours(24) : NOW.plusSeconds(1),
                kind.equals("leaseOnly") ? NOW.plusHours(24) : NOW.plusSeconds(1));
    }
    private void armTimeAdvance(String point,String kind) { advancePoint=point; deadlineKind=kind; }
    private void printTimeOutcome(String operation,Object outcome) {
        System.out.println("BUG355_R02_OUTCOME operation="+operation+" point="+advancePoint+" deadline="+deadlineKind
                +" freshClock="+businessClock.instant()+" outcome="+(outcome instanceof RuntimeException e ? e.getMessage() : "ACCEPTED")
                +" receipt="+count("nx_compute_receipt")+" ledger="+count("nx_wallet_ledger")
                +" runtime="+jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)
                +" receiptWritesAfterAdvance="+receiptsAfterAdvance+" runtimeWritesAfterAdvance="+runtimeMarksAfterAdvance);
    }

    @ParameterizedTest @ValueSource(strings={"unpaid", "trial", "sandbox", "deleted", "expiredTarget", "completedTarget", "paused", "errorRuntime", "otherAgent"})
    void claimCannotCreateTestLivenessForIneligibleResourceOrInvalidTask(String change) {
        switch(change) {
            case "unpaid" -> jdbc.update("UPDATE nx_order SET payment_status='UNPAID'");
            case "trial" -> jdbc.update("UPDATE nx_user_device SET source_channel='TRIAL'");
            case "sandbox" -> jdbc.update("UPDATE nx_user SET sandbox=1");
            case "deleted" -> jdbc.update("UPDATE nx_user_device SET is_deleted=1");
            case "expiredTarget" -> jdbc.update("UPDATE nx_compute_task SET lease_expires_at=?",NOW.minusSeconds(1));
            case "completedTarget" -> jdbc.update("UPDATE nx_compute_task SET status='COMPLETED'");
            case "paused" -> jdbc.update("UPDATE nx_compute_dc_ops_state SET dispatch_paused=1");
            case "errorRuntime" -> jdbc.update("UPDATE nx_user_device_runtime SET online_status='ERROR'");
            case "otherAgent" -> jdbc.update("UPDATE nx_user_device_runtime SET online_status='ONLINE',agent_version='other-agent'");
        }
        String previous=jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class);
        assertThatThrownBy(this::claim).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo(previous);
        assertThat(jdbc.queryForObject("SELECT model_name FROM nx_compute_task",String.class)).isEqualTo("BGE-M3");
        assertNoRewards();
    }

    @ParameterizedTest @ValueSource(strings={"insertWalletLedger","insertEarningEvent","insertCloudShareNexLedger","insertCloudShareNexEvent",
            "completeAssignment","closeTestWorkerRuntime","audit","outbox","revokeAfterCredit"})
    void injectedFailureRollsBackRealSqlMoneyReceiptNonceRuntimeAuditAndOutbox(String point) {
        claim(); failure=point;
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"complete-failed",result())).isInstanceOf(RuntimeException.class);
        assertNoRewards();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT proof_consumed_at FROM nx_compute_task",LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime",String.class)).isEqualTo(grant.taskNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='TASK_ASSIGNMENT_COMPLETED'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_admin_idempotency_record WHERE idempotency_key='complete-failed'",String.class)).isEqualTo("FAILED");
        failure=null; env.setProperty("nexion.compute-task.test-worker.enabled","true");
        assertThat(app.testWorkerComplete(grant,grant.taskNo(),"complete-recovery",result()).getCode()).isZero();
        assertBalances("10.25","23");
    }

    @Test void correctHashWrongSumIsRejectedByActualSharedTransactionWithoutAnyReward() {
        claim(); var good=result();
        String text=new String(Base64.getDecoder().decode(good.resultArtifactBase64()),StandardCharsets.UTF_8).replace("sum=-1184","sum=-1183");
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        var wrong=new CompleteRequest(good.specVersion(),good.inputHash(),TestComputeWorkerService.sha256(bytes),
                Base64.getEncoder().encodeToString(bytes),good.proofNonce(),good.proofTimestamp());
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"complete-wrong",wrong)).hasMessage("TEST_COMPUTE_RESULT_INVALID");
        assertNoRewards();
    }

    @ParameterizedTest @ValueSource(strings={"owner","instance","taskConfig","lease","nonceConsumed","kill","vram","pause","deactivated","runtimeTaken","runtimeStale"})
    void lockedCurrentFactsAlwaysFenceProofAndPreventFunds(String change) {
        claim(); var request=result();
        switch(change) {
            case "owner" -> jdbc.update("UPDATE nx_user_device SET user_id=8");
            case "instance" -> jdbc.update("UPDATE nx_user_device SET instance_no='OTHER'");
            case "taskConfig" -> jdbc.update("UPDATE nx_compute_task SET task_config_id='OTHER'");
            case "lease" -> jdbc.update("UPDATE nx_compute_task SET lease_expires_at=?",NOW.minusSeconds(1));
            case "nonceConsumed" -> jdbc.update("UPDATE nx_compute_task SET proof_consumed_at=?",NOW.minusSeconds(1));
            case "kill" -> jdbc.update("UPDATE nx_admin_device_task SET kill_init='kill'");
            case "vram" -> jdbc.update("UPDATE nx_admin_device_task SET min_vram='99GB'");
            case "pause" -> jdbc.update("UPDATE nx_compute_dc_ops_state SET dispatch_paused=1");
            case "deactivated" -> jdbc.update("UPDATE nx_user_device SET status='DEACTIVATED',activated_at=NULL");
            case "runtimeTaken" -> jdbc.update("UPDATE nx_user_device_runtime SET agent_version='someone-else'");
            case "runtimeStale" -> jdbc.update("UPDATE nx_user_device_runtime SET heartbeat_at=?",NOW.minusSeconds(121));
        }
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"complete-guard",request)).isInstanceOf(RuntimeException.class);
        assertNoRewards();
    }

    @Test void dailyNexIsNotPaidAgainAndPendingDeactivationKeepsOriginalSharedTransition() {
        claim();
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(7,'CLOUD_SHARE_DAILY:11:2026-10-02','COMPUTE_TASK_REWARD','NEX','IN',3,20,'SUCCESS')");
        jdbc.update("UPDATE nx_user_device SET pending_deactivate=1");
        assertThat(app.testWorkerComplete(grant,grant.taskNo(),"complete-daily",result()).getCode()).isZero();
        assertBalances("10.25","20");
        assertThat(jdbc.queryForObject("SELECT reward_nex FROM nx_compute_receipt",BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device",String.class)).isEqualTo("DEACTIVATED");
        assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime",String.class)).isEqualTo("USER_DEACTIVATED");
        assertThat(jdbc.queryForObject("SELECT path FROM nx_audit_log WHERE action='USER_DEVICE_DEFERRED_DEACTIVATED'",String.class))
                .isEqualTo("/api/test/compute-workers/v1/tasks/"+grant.taskNo()+"/complete");
    }

    @Test void successfulReleaseClosesOnlyOwnLivenessAndReplayCannotCreateRewardsOrCompleteOffline() {
        claim();
        assertThat(app.testWorkerRelease(grant,grant.taskNo(),fixed("RELEASE")).getData()).containsEntry("released",true);
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime",String.class)).isEqualTo(grant.taskNo());
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo("RUNNING");
        assertThat(app.testWorkerRelease(grant,grant.taskNo(),fixed("RELEASE")).getData()).containsEntry("released",true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='TEST_COMPUTE_WORKER_RELEASED'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"after-release",result())).isInstanceOf(RuntimeException.class);
        assertNoRewards();
    }

    @Test void releaseAndDisabledExpiryCleanupOnlyCloseOwnMarkerWithoutChangingTaskOrFunds() {
        claim();
        jdbc.update("UPDATE nx_user_device_runtime SET agent_version='other-agent'");
        assertThat(app.testWorkerRelease(grant,grant.taskNo(),fixed("RELEASE")).getData()).containsEntry("released",false);
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("ONLINE");
        jdbc.update("UPDATE nx_user_device_runtime SET agent_version=?,heartbeat_at=?",worker.marker(grant),NOW.minusSeconds(121));
        env.setProperty("nexion.compute-task.test-worker.enabled","false");
        env.setProperty("nexion.compute-task.test-worker.credential-sha256","");
        worker.cleanupExpiredRuntime();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT actor_type FROM nx_audit_log WHERE action='TEST_COMPUTE_WORKER_RUNTIME_EXPIRED'",String.class)).isEqualTo("SYSTEM");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo("RUNNING");
        assertNoRewards();
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"after-revoke",result())).hasMessage("TEST_COMPUTE_WORKER_DISABLED");
    }

    private ffdd.opsconsole.shared.api.ApiResult<Map<String,Object>> claim() { return app.testWorkerClaim(grant,grant.taskNo(),fixed("CLAIM")); }
    private String fixed(String op) { return "TEST355-"+op+"-"+TestComputeWorkerService.sha256(grant.taskNo().getBytes(StandardCharsets.UTF_8)); }
    private CompleteRequest result() {
        var task=mapper.lockAssignment(7L,grant.taskNo(),"PRODUCTION");
        var input=worker.input(grant,task); byte[] bytes=worker.expectedResult(input);
        // The fixed original fixture matches an independently executed Python oracle, not just Java self-comparison.
        if(grant.taskNo().equals("CTA-TEST-ONE")) assertThat(TestComputeWorkerService.sha256(bytes)).isEqualTo("08848de76d730b47d5cde8e594d6f58e0a6eae32dd3200ffa4206a5c8df621cc");
        return new CompleteRequest(TestComputeWorkerService.SPEC,input.hash(),TestComputeWorkerService.sha256(bytes),Base64.getEncoder().encodeToString(bytes),task.completionNonce(),INSTANT.toEpochMilli());
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private void assertBalances(String usdt,String nex) {
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo(usdt);
        assertThat(jdbc.queryForObject("SELECT nex_available FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo(nex);
    }
    private void assertNoRewards() { assertBalances("10","20"); for(String table:List.of("nx_compute_receipt","nx_earning_event","nx_event_outbox")) assertThat(count(table)).as(table).isZero(); assertThat(count("nx_wallet_ledger")).isZero(); }

    private void configure(String taskNo) {
        env=TestComputeWorkerServiceTest.environment(taskNo); env.setActiveProfiles("prod");
        MybatisConfiguration config=new MybatisConfiguration(new Environment("bug355-local",new SpringManagedTransactionFactory(),source));
        var global=new GlobalConfig();global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new ffdd.opsconsole.shared.config.MybatisMetaObjectHandler(businessClock));
        GlobalConfigUtils.setGlobalConfig(config,global);
        config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(AppTaskAssignmentMapper.class,AuditLogMapper.class,EventOutboxMapper.class,AdminIdempotencyRecordMapper.class)) config.addMapper(type);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper=controlled(AppTaskAssignmentMapper.class,session.getMapper(AppTaskAssignmentMapper.class));
        var auditMapper=controlled(AuditLogMapper.class,session.getMapper(AuditLogMapper.class));
        var policy=mock(A2RuntimePolicy.class);when(policy.schemaVersion()).thenReturn("v1");
        var audit=new AuditLogService(auditMapper,new AuditLogSanitizer(json),new ApplicationNameProperties(),new AuditProperties(),mock(AdminMapper.class),policy);
        var expiry=transactional(new AdminIdempotencyExpiryTransitionExecutor(session.getMapper(AdminIdempotencyRecordMapper.class)));
        var executor=transactional(new AdminIdempotencyTransactionExecutor(session.getMapper(AdminIdempotencyRecordMapper.class),json,expiry));
        var idempotency=new AdminIdempotencyService(executor,businessClock);
        var outbox=new EventOutboxService(controlled(EventOutboxMapper.class,session.getMapper(EventOutboxMapper.class)),json,new OutboxProperties(),mock(A4RuntimePolicyService.class));
        var proof=mock(ComputeTaskProofVerifier.class);when(proof.sourceEnvironment()).thenReturn("PRODUCTION");
        worker=transactional(new TestComputeWorkerService(mapper,audit,businessClock,env));
        var target=new AppTaskAssignmentService(mapper,idempotency,outbox,audit,proof,env,businessClock,worker);
        app=transactional(target);
        grant=worker.authenticate("Bearer "+TestComputeWorkerServiceTest.TOKEN);
    }
    @SuppressWarnings("unchecked") private <T>T transactional(T target) {
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    @SuppressWarnings("unchecked") private <T>T controlled(Class<T> type,T target) {
        return (T)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(timeAdvanced && method.getName().equals("insertReceipt")) receiptsAfterAdvance++;
            if(timeAdvanced && method.getName().equals("markTestWorkerOnline")) runtimeMarksAfterAdvance++;
            if(method.getName().equals(failure)) return 0;
            if("audit".equals(failure) && method.getName().equals("insertAuditLog")) throw new IllegalStateException("LOCAL_TEST_AUDIT_FAILURE");
            if("outbox".equals(failure) && method.getName().equals("insertEvent") && ++outboxCalls==2) throw new IllegalStateException("LOCAL_TEST_OUTBOX_FAILURE");
            try {
                Object result=method.invoke(target,args);
                boolean atBoundary=Objects.equals(advancePoint,method.getName())
                        || ("lastOutbox".equals(advancePoint) && method.getName().equals("insertEvent") && ++timeOutboxCalls==2);
                if(!timeAdvanced && atBoundary) {
                    timeAdvanced=true;
                    businessClock.current=INSTANT.plusSeconds("equality".equals(deadlineKind) ? 1 : 2);
                    stateAtAdvance=Map.of("receipt",count("nx_compute_receipt"),"ledger",count("nx_wallet_ledger"),
                            "outbox",count("nx_event_outbox"),"task",jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class));
                    System.out.println("BUG355_R02_REAL_SQL_TIME_ADVANCE point="+advancePoint+" deadline="+deadlineKind
                            +" actualResult="+result+" now="+businessClock.instant()+" actualState="+stateAtAdvance);
                }
                if("revokeAfterCredit".equals(failure) && method.getName().equals("insertEarningEvent")) {
                    env.setProperty("nexion.compute-task.test-worker.enabled","false");
                }
                return result;
            } catch(InvocationTargetException exception) { throw exception.getCause(); }
        });
    }

    private static class ControlledClock extends Clock {
        private volatile Instant current=INSTANT;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current,zone); }
        @Override public Instant instant() { return current; }
    }

    private void createTables() throws Exception {
        String sql=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_user","nx_product","nx_order","nx_order_item","nx_user_device","nx_user_device_runtime",
                "nx_compute_dc_ops_state","nx_compute_e3_config","nx_compute_task","nx_compute_receipt","nx_compute_device_task_lock",
                "nx_user_wallet","nx_wallet_ledger","nx_earning_event","nx_config_item","nx_onboarding_calibration",
                "nx_admin_idempotency_record","nx_audit_log","nx_event_outbox","nx_event_schema_registry","nx_event_schema_property")) {
            var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+" \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(sql);
            assertThat(matcher.find()).as("formal schema DDL "+table).isTrue();jdbc.execute(matcher.group());
        }
        // Existing canonical migration; schema.sql predates wallet sandbox. No production schema change.
        jdbc.execute("ALTER TABLE nx_user_wallet ADD COLUMN sandbox TINYINT(1) NOT NULL DEFAULT 0");
        // Minimal legacy E2 projection, using only actual AppTaskAssignmentMapper's current columns.
        jdbc.execute("CREATE TABLE nx_admin_device_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(64) UNIQUE,name VARCHAR(128),task_class VARCHAR(32),model_name VARCHAR(128),min_reward DECIMAL(18,6),max_reward DECIMAL(18,6),min_vram VARCHAR(32),status VARCHAR(32),kill_init VARCHAR(32),updated_at DATETIME,is_deleted TINYINT DEFAULT 0)");
        String lifecycle=Files.readString(Path.of("scripts/migrations/20260810_ab_pending_closure.sql"));
        var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS nx_admin_event_lifecycle \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(lifecycle);
        assertThat(matcher.find()).isTrue();jdbc.execute(matcher.group());
    }

    private void seed() {
        jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,created_at) VALUES(7,'84','912345678','127.0.0.1','LOCAL_FIXTURE_NOT_LOGIN','local fixture','FIXTURE7','2026-09-01')");
        jdbc.update("INSERT INTO nx_product(id,product_no,name,product_type,status,daily_nex) VALUES(1,'cloud-share','TEST fixture paid share','SHARE','ACTIVE',3)");
        jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,quantity,amount_usdt,payment_status,order_status,activation_status) VALUES(7,'ORDER-LOCAL',1,1,19.9,'PAID','COMPLETED','ACTIVATED')");
        jdbc.update("INSERT INTO nx_user_device(id,user_id,source_order_no,product_id,product_code,instance_no,name,device_type,status,vram_total_gb,dc_location,daily_nex,purchased_at,activated_at) VALUES(11,7,'ORDER-LOCAL',1,'cloud-share','NEX-TEST-INSTANCE','TEST fixture','SHARE','ACTIVE',8,'LOCAL',3,?,?)",NOW.minusDays(1),NOW.minusDays(1));
        jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,online_status,active_task_no,client_name,heartbeat_at) VALUES(11,'OFFLINE','CTA-TEST-ONE','UVEL App',?)",NOW.minusMinutes(5));
        jdbc.update("INSERT INTO nx_compute_dc_ops_state(dc_location,dispatch_paused) VALUES('LOCAL',0)");
        jdbc.update("INSERT INTO nx_admin_device_task(task_id,name,task_class,model_name,min_reward,max_reward,min_vram,status,kill_init,updated_at) VALUES('TASK-EM','Legacy model task','EM','BGE-M3',0.25,0.25,'8GB','active','pending',?)",NOW);
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,task_config_id,task_name,model_name,reward_usdt,required_seconds,completion_nonce,proof_expires_at,client_name,status,started_at,lease_expires_at) VALUES('CTA-TEST-ONE',7,11,'EM','TASK-EM','Legacy model task','BGE-M3',0.25,5,?,?,'UVEL App','RUNNING',?,?)",TestComputeWorkerServiceTest.NONCE,NOW.plusHours(24),NOW.minusSeconds(10),NOW.plusHours(24));
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available,lifetime_earned) VALUES(7,10,20,0)");
        Map<String,String> capacity=Map.ofEntries(Map.entry("capacityBand1DeltaPct","-3"),Map.entry("capacityBand2DeltaPct","-6"),Map.entry("capacityBand3DeltaPct","-23.7"),
                Map.entry("stageEarlyEnd","3"),Map.entry("stageMidEnd","8"),Map.entry("cycleMonths","13"),Map.entry("capacityFloorPct","22"),Map.entry("capacitySubsidyDays","30"),
                Map.entry("taskLockS1","30"),Map.entry("taskLockPro","150"),Map.entry("taskLockRack","480"),Map.entry("capacityApplyToPhone","false"),Map.entry("capacityApplyToCloudShare","false"),
                Map.entry("capacityApplyToPcGpu","false"),Map.entry("capacityApplyToS1","true"),Map.entry("capacityApplyToPro","true"),Map.entry("capacityApplyToProV2","true"),Map.entry("capacityApplyToRackP1","true"),Map.entry("capacityApplyToRackP2","true"));
        capacity.forEach((key,value)->jdbc.update("INSERT INTO nx_compute_e3_config(config_key,config_value) VALUES(?,?)",key,value));
        for(String event:List.of("task.completed","earnings.credited","device.deactivated")) {
            jdbc.update("INSERT INTO nx_event_schema_registry(event_name,owner_domain,family_key,producer,is_server_authoritative,sampling_policy,current_revision,created_by,reason) VALUES(?,'E','device','server',1,'100',1,'local-fixture','isolated contract fixture')",event);
            jdbc.update("INSERT INTO nx_admin_event_lifecycle(event_name,lifecycle_state) VALUES(?,'full')",event);
            // Same property names/types as the canonical 20260831 business/device event migrations.
            Map<String,String> fields=event.equals("device.deactivated")
                    ? Map.of("device_id","id","instance_no","id","previous_status","enum","status","enum","row_version","number")
                    : Map.of("task_id","id","task_no","id","device_id","id","receipt_no","id","amount_usdt","number");
            fields.forEach((name,type)->jdbc.update("INSERT INTO nx_event_schema_property(schema_id,property_name,property_type,required_field,registry_revision) SELECT id,?,?,1,1 FROM nx_event_schema_registry WHERE event_name=?",name,type,event));
        }
    }
}
