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
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.auth.application.AppLoginContext;
import ffdd.opsconsole.auth.application.AppSessionSurface;
import ffdd.opsconsole.auth.application.AppSingleSessionService;
import ffdd.opsconsole.auth.mapper.AppLoginAttemptMapper;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.onboarding.application.PhoneInstallationService;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.PhoneInstallationMapper;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
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
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in isolated MySQL; explicit test credentials, verified target and independently owned schemas. */
@EnabledIfEnvironmentVariable(named="UVEL_BUG355_MYSQL_IT", matches="true")
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
class TestComputeWorkerSettlementSpringTransactionMySqlIntegrationTest {
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
    private boolean phoneMode;
    private AppSingleSessionService appSessions;
    private TransactionTemplate authTransaction;
    private static final byte[] PHONE_PROOF_KEY="isolated-phone-executor-key-32-bytes".getBytes(StandardCharsets.UTF_8);

    @Test void realFleetMapperFindsOnlyNewPaidActivatedHardwareAndLocksTheCurrentOrder() throws Exception {
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("fleet-isolated",new SpringManagedTransactionFactory(),source));
        config.addMapper(TestCloudExecutorMapper.class);
        var fleetSession=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var fleet=fleetSession.getMapper(TestCloudExecutorMapper.class);
        jdbc.update("INSERT INTO nx_product(id,product_no,name,product_type,status) VALUES(2,'stellarbox-s1','TEST S1','SERVER','ACTIVE')");
        jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,quantity,subtotal_usdt,amount_usdt,payment_status,order_status,activation_status) VALUES(7,'ORDER-FLEET',2,1,200,200,'PAID','COMPLETED','ACTIVATED')");
        jdbc.update("INSERT INTO nx_user_device(id,user_id,source_order_no,product_id,product_code,instance_no,name,device_type,status,vram_total_gb,dc_location,purchased_at,activated_at) VALUES(12,7,'ORDER-FLEET',2,'stellarbox-s1','NEX-FLEET-12','TEST S1','SERVER','ACTIVE',96,'LOCAL',?,?)",NOW,NOW);
        var cutoff=NOW.minusHours(1);
        assertThat(fleet.fleetCandidates(cutoff,0,1202,101)).extracting(TestCloudExecutorMapper.FleetCandidate::deviceId).containsExactly(12L);
        assertThat(mapper.managedFleet(7,12,cutoff,1202)).isEqualTo(1);
        jdbc.update("UPDATE nx_order SET amount_usdt=0 WHERE order_no='ORDER-FLEET'");
        assertThat(fleet.fleetCandidates(cutoff,0,1202,101)).extracting(TestCloudExecutorMapper.FleetCandidate::deviceId).containsExactly(12L);
        jdbc.update("UPDATE nx_order SET payment_status='UNPAID' WHERE order_no='ORDER-FLEET'");
        assertThat(fleet.fleetCandidates(cutoff,0,1202,101)).isEmpty();
        jdbc.update("UPDATE nx_order SET payment_status='PAID' WHERE order_no='ORDER-FLEET'");
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.execute(status->{
            assertThat(fleet.liveFleetBinding(cutoff,7,12,"NEX-FLEET-12",false)).isEqualTo(1);
            assertThat(fleet.register(new TestCloudExecutorMapper.Binding("fleet-12",7,12,"NEX-FLEET-12","TASK-EM","bge-m3:567m",TestCloudExecutorServiceTest.DIGEST),NOW)).isEqualTo(1);
            return null;
        });
        assertThat(mapper.registeredFleet(7,12)).isEqualTo(1);
        jdbc.update("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=12");
        assertThat(fleet.fleetCandidates(cutoff,0,1202,101)).isEmpty();
        assertThat(fleet.liveFleetBinding(cutoff,7,12,"NEX-FLEET-12",false)).isNull();
        assertThat(fleet.liveFleetBinding(cutoff,7,12,"NEX-FLEET-12",true)).isEqualTo(1);
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,task_config_id,task_name,model_name,reward_usdt,required_seconds,completion_nonce,proof_expires_at,client_name,status,started_at,lease_expires_at) VALUES('CTA-FLEET',7,12,'EM','TASK-EM','TEST BGE','TEST_BGE_M3_V1',0.25,5,?,?,'UVEL TEST BGE-M3','RUNNING',?,?)",TestComputeWorkerServiceTest.NONCE,NOW.plusHours(24),NOW.minusSeconds(10),NOW.plusHours(24));
        assertThat(fleet.insert(new TestCloudExecutorMapper.Execution("CTA-FLEET","fleet-12",7,12,"NEX-FLEET-12","TASK-EM",
                "a".repeat(64),"b".repeat(64),"[]","c".repeat(64),"bge-m3:567m",TestCloudExecutorServiceTest.DIGEST,
                "d".repeat(64),null,null,null,null,NOW))).isEqualTo(1);
        assertThat(fleet.fleetCandidates(cutoff,0,1202,101)).extracting(TestCloudExecutorMapper.FleetCandidate::deviceId).containsExactly(12L);
        jdbc.update("UPDATE nx_user_device SET pending_deactivate=0 WHERE id=12");
        jdbc.update("UPDATE nx_order SET payment_status='REFUNDED' WHERE order_no='ORDER-FLEET'");
        assertThat(fleet.fleetCandidates(cutoff,0,1202,101)).isEmpty();
        assertThat(fleet.liveFleetBinding(cutoff,7,12,"NEX-FLEET-12",false)).isNull();
        assertThat(fleet.binding("fleet-12").instanceNo()).isEqualTo("NEX-FLEET-12");
        jdbc.update("UPDATE nx_order SET payment_status='PAID' WHERE order_no='ORDER-FLEET'");
        jdbc.update("UPDATE nx_user_device SET purchased_at=? WHERE id=12",cutoff.minusSeconds(1));
        assertThat(fleet.fleetCandidates(cutoff,0,1202,101)).isEmpty();
    }

    @BeforeEach void fixture() throws Exception {
        String serverUrl=System.getenv("NEXION_TEST_DB_SERVER_URL");
        String username=System.getenv("NEXION_TEST_DB_USERNAME");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        String expectedDirectory=System.getenv("NEXION_TEST_DB_EXPECTED_DATADIR");
        assertThat(serverUrl).matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):[0-9]+/([?].*)?");
        int port=java.net.URI.create(serverUrl.substring("jdbc:".length())).getPort();
        assertThat(port).isBetween(1,65535);
        assertThat(username).isNotBlank();assertThat(password).isNotBlank();
        assertThat(expectedDirectory).isNotBlank();
        // Target checks precede CREATE DATABASE; a runtime database URL is never accepted.
        admin = DriverManager.getConnection(serverUrl, username, password);
        try (var statement=admin.createStatement(); var rows=statement.executeQuery("SELECT @@port,@@datadir,DATABASE()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(port);
            assertThat(rows.getString(2).replace('\\','/').replaceAll("/+$",""))
                    .isEqualTo(expectedDirectory.replace('\\','/').replaceAll("/+$",""));
            assertThat(rows.getString(3)).isNull();
        }
        try(var statement=admin.createStatement(); var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.PLUGINS WHERE PLUGIN_NAME='mysqlx' AND PLUGIN_STATUS='ACTIVE'")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
        }
        System.out.println("BUG355_LOCAL_MYSQL_PIN_OK port="+port+" datadir="+expectedDirectory);
        schema="uvel_bug355_"+UUID.randomUUID().toString().replace("-", "");
        assertThat(OWN.matcher(schema).matches()).isTrue();
        try(var statement=admin.createStatement()) { statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        created=true;
        int query=serverUrl.indexOf('?');
        String fixtureUrl=serverUrl.substring(0,query<0?serverUrl.length():query)+schema+(query<0?"":serverUrl.substring(query));
        source=new DriverManagerDataSource(fixtureUrl, username, password);
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE expires_at <= NOW()",Integer.class)).isZero();
        var replay=app.testWorkerComplete(grant,grant.taskNo(),"complete-one",request);
        assertThat(replay.getData()).containsEntry("receiptNo",completed.getData().get("receiptNo"));
        assertBalances("10.25","23");
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"complete-other",request)).hasMessage("TASK_ASSIGNMENT_PROOF_REPLAYED");
        var changed=new CompleteRequest(request.specVersion(), request.inputHash(),"b".repeat(64),request.resultArtifactBase64(),request.proofNonce(),request.proofTimestamp());
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"complete-one",changed)).hasMessageContaining("IDEMPOTENCY");
        assertThat(count("nx_wallet_ledger")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void realPhoneCompletionVersusAppTakeoverSerializesFundsAndNonce(boolean takeoverFirst) throws Exception {
        preparePhoneFixture();
        var proof=phoneProof();var next=phoneContext("phone-b");
        var winnerLocked=new CountDownLatch(1);var contenderStarted=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var winner=pool.submit(()->authTransaction.execute(status->{
                session.getMapper(AuthSessionMapper.class).lockAccount(7);
                winnerLocked.countDown();awaitPhoneRace(contenderStarted);
                return takeoverFirst ? appSessions.issue(7,next,"local",30)
                        : app.complete(7L,"CTA-TEST-ONE","phone-complete",proof);
            }));
            var contender=pool.submit(()->{
                awaitPhoneRace(winnerLocked);contenderStarted.countDown();
                if(takeoverFirst) {
                    assertThatThrownBy(()->app.complete(7L,"CTA-TEST-ONE","phone-complete",proof))
                            .hasMessage("PHONE_EXECUTION_SESSION_REQUIRED");
                } else authTransaction.executeWithoutResult(status->appSessions.issue(7,next,"local",30));
            });
            assertThat(winner.get(20,TimeUnit.SECONDS)).isNotNull();contender.get(20,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertPhoneSettlement(!takeoverFirst);
        assertThat(jdbc.queryForObject("SELECT installation_id FROM nx_app_session_state",String.class)).isEqualTo("phone-b");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE revoked_at IS NULL AND session_ended_reason IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT installation_id FROM nx_phone_binding",String.class)).isEqualTo("phone-a");
        assertThat(jdbc.queryForObject("SELECT execution_installation_id FROM nx_phone_binding",String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_type='account.app_session_replaced'",Integer.class)).isEqualTo(2);
        var beforeReplay=phoneSettlementSnapshot();
        if(!takeoverFirst) {
            var replay=app.complete(7L,"CTA-TEST-ONE","phone-complete",proof);
            assertThat(replay.getCode()).isZero();
            assertThat(json.valueToTree(replay.getData()).get("receiptNo").asText()).isEqualTo("CTR-CTA-TEST-ONE");
        }
        assertThatThrownBy(()->app.complete(7L,"CTA-TEST-ONE","phone-second-command",proof))
                .hasMessage("PHONE_EXECUTION_SESSION_REQUIRED");
        assertThat(phoneSettlementSnapshot()).isEqualTo(beforeReplay);
    }

    @ParameterizedTest @ValueSource(strings={"signature","completeAssignment","audit","outbox"})
    void realPhoneProofAndSettlementFailureRollBackBeforeTakeover(String point) throws Exception {
        preparePhoneFixture();var proof=phoneProof();
        if(point.equals("signature")) proof=new AppTaskCompleteRequest(proof.resultHash(),proof.proofMode(),
                proof.executorId(),proof.proofNonce(),proof.proofTimestamp(),"0".repeat(64));
        var submitted=proof;var before=phoneSettlementSnapshot();failure=point;
        try {
            var rejected=assertThatThrownBy(()->app.complete(7L,"CTA-TEST-ONE","phone-failed",submitted));
            switch(point) {
                case "signature" -> rejected.hasMessage("TASK_ASSIGNMENT_PROOF_SIGNATURE_INVALID");
                case "completeAssignment" -> rejected.hasMessage("TASK_ASSIGNMENT_PROOF_REPLAYED");
                case "audit" -> rejected.hasMessage("LOCAL_TEST_AUDIT_FAILURE");
                default -> rejected.hasMessage("LOCAL_TEST_OUTBOX_FAILURE");
            }
        } finally {failure=null;}
        assertThat(phoneSettlementSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT proof_consumed_at FROM nx_compute_task",LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_admin_idempotency_record WHERE idempotency_key='phone-failed'",String.class)).isEqualTo("FAILED");
        var next=phoneContext("phone-b");
        authTransaction.executeWithoutResult(status->appSessions.issue(7,next,"local",30));
        assertPhoneSettlement(false);
        assertThatThrownBy(()->app.complete(7L,"CTA-TEST-ONE","phone-after-stop",phoneProof()))
                .hasMessage("PHONE_EXECUTION_SESSION_REQUIRED");
        assertPhoneSettlement(false);
    }

    private void preparePhoneFixture() throws Exception {
        String schemaSql=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_user_session","nx_user_security","nx_phone_binding")) {
            int start=schemaSql.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
            jdbc.execute(schemaSql.substring(start,schemaSql.indexOf(';',start)+1));
        }
        String migration=Files.readString(Path.of("scripts/migrations/20261019_app_single_session.sql"));
        String statements=migration.lines().filter(line->!line.stripLeading().startsWith("--"))
                .reduce("",(left,right)->left+"\n"+right);
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            for(String sql:statements.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
        jdbc.update("UPDATE nx_user_device SET device_type='PHONE',source_channel='ONBOARDING'");
        jdbc.update("INSERT INTO nx_phone_binding VALUES(7,'PRODUCTION','','phone-a','phone-a',11,'hardware',NOW(6),1)");
        jdbc.update("INSERT INTO nx_onboarding_calibration(user_id,device_id,user_device_id,signal_json,derived_json,comparison_json,config_revision,idempotency_key,request_hash,activation_status) VALUES(7,'phone-a',11,'{}','{}','{}',1,'phone-fixture',?,'ACTIVE')","a".repeat(64));
        phoneMode=true;configure("CTA-TEST-ONE");
        authTransaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var initial=phoneContext("phone-a");
        authTransaction.executeWithoutResult(status->appSessions.issue(7,initial,"local",30));
        jdbc.update("UPDATE nx_user_device_runtime SET online_status='ONLINE',battery_level=80,network_reachable=1,heartbeat_at=?,paused_reason=NULL",NOW);
    }

    private AppLoginContext phoneContext(String installation) {
        return new AppLoginContext(AppSessionSurface.APP,installation,true,
                "attempt_"+UUID.randomUUID().toString().replace("-",""),"b".repeat(64),"PASSWORD","fixture-user-7");
    }

    private AppTaskCompleteRequest phoneProof() throws Exception {
        String canonical="7\nCTA-TEST-ONE\n11\nNEX-TEST-INSTANCE\n"+"f".repeat(64)+"\n"+INSTANT.toEpochMilli()+"\n"+TestComputeWorkerServiceTest.NONCE;
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(PHONE_PROOF_KEY,"HmacSHA256"));
        return new AppTaskCompleteRequest("f".repeat(64),"PRODUCTION","phone-executor",TestComputeWorkerServiceTest.NONCE,
                INSTANT.toEpochMilli(),HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
    }

    private void assertPhoneSettlement(boolean completed) {
        assertBalances(completed?"10.25":"10","20");
        for(String table:List.of("nx_compute_receipt","nx_wallet_ledger","nx_earning_event"))
            assertThat(count(table)).as(table).isEqualTo(completed?1:0);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo(completed?"COMPLETED":"CANCELLED");
        assertThat(jdbc.queryForObject("SELECT proof_consumed_at IS NOT NULL FROM nx_compute_task",Boolean.class)).isEqualTo(completed);
        assertThat(jdbc.queryForObject("SELECT completion_nonce FROM nx_compute_task",String.class)).isEqualTo(TestComputeWorkerServiceTest.NONCE);
        for(String event:List.of("task.completed","earnings.credited"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_type=?",Integer.class,event)).isEqualTo(completed?1:0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='TASK_ASSIGNMENT_COMPLETED'",Integer.class)).isEqualTo(completed?1:0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='TASK_ASSIGNMENT_PHONE_REPLACED'",Integer.class)).isEqualTo(completed?0:1);
    }

    private Map<String,Object> phoneSettlementSnapshot() {
        var result=new LinkedHashMap<String,Object>();
        for(String table:List.of("nx_user_wallet","nx_compute_task","nx_compute_receipt","nx_wallet_ledger",
                "nx_earning_event","nx_user_device_runtime","nx_compute_device_task_lock","nx_support_wallet_journal",
                "nx_support_fund_operation"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        result.put("fundLegs",jdbc.queryForList("SELECT * FROM nx_support_fund_operation_leg ORDER BY wallet_incarnation,revision"));
        result.put("fundParties",jdbc.queryForList("SELECT * FROM nx_support_fund_operation_party ORDER BY operation_id,customer_id"));
        result.put("audit",count("nx_audit_log"));result.put("outbox",count("nx_event_outbox"));
        return result;
    }

    private static void awaitPhoneRace(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("phone race latch timeout");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
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
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(7,'CLOUD_SHARE_DAILY:11:2026-10-02','COMPUTE_TASK_REWARD','UVT','IN',3,20,'SUCCESS')");
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE expires_at <= NOW()",Integer.class)).isZero();
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

    @ParameterizedTest @ValueSource(strings={"ONLINE","OFFLINE"})
    void continuousNormalReleaseCancelsOnlyOwnUnsettledTaskAndNextUsesFreshOriginalRail(String online) {
        continuousFixture();
        var originalProof=result();
        var lease=jdbc.queryForObject("SELECT lease_expires_at FROM nx_compute_task",LocalDateTime.class);
        if(online.equals("OFFLINE")) {
            jdbc.update("UPDATE nx_user_device_runtime SET heartbeat_at=?",NOW.minusSeconds(121));
            worker.cleanupExpiredRuntime();
        }
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo(online);
        var released=app.continuousWorkerRelease(continuousIdentity(),grant.taskNo(),fixed("RELEASE"));
        assertThat(released.getData()).containsEntry("released",true).containsEntry("taskStatus","CANCELLED")
                .containsEntry("serverCanonical",true).containsEntry("deploymentScope","TEST");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT proof_consumed_at IS NOT NULL FROM nx_compute_task",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM nx_compute_task",LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_expires_at FROM nx_compute_task",LocalDateTime.class)).isEqualTo(lease);
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime",String.class)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime",String.class)).isNull();
        assertNoRewards();
        assertThatThrownBy(() -> app.testWorkerComplete(grant,grant.taskNo(),"after-v2-release",originalProof))
                .hasMessage("TASK_ASSIGNMENT_STATE_INVALID");
        var next=app.continuousWorkerNext(continuousIdentity(),"TEST355-NEXT-"+UUID.randomUUID().toString().replace("-","")).getData();
        assertThat(next.get("taskNo")).isNotEqualTo(grant.taskNo());
        assertThat(next.get("proofNonce")).isNotEqualTo(originalProof.proofNonce());
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,grant.taskNo())).isEqualTo("CANCELLED");
        assertThat(count("nx_compute_receipt")).isZero(); assertThat(count("nx_wallet_ledger")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"proofConsumed","receipt","ledger","foreignMarker","foreignClient","commercialTask",
            "foreignOwner","foreignInstance","foreignConfig","foreignRuntimeTask","unownedDevice","otherLedger"})
    void continuousReleaseFencesProofFundsAndForeignBindingsWithoutCancelling(String change) {
        continuousFixture();
        switch(change) {
            case "proofConsumed" -> jdbc.update("UPDATE nx_compute_task SET proof_consumed_at=?",NOW);
            case "receipt" -> jdbc.update("INSERT INTO nx_compute_receipt(receipt_no,user_id,user_device_id,task_no,task_type,client_name,reward_usdt,reward_nex,earning_status,proof_hash,completed_at) VALUES(?,?,?,?,'EM',?,0.045005,0,'CREDITED','synthetic-hash',?)",
                    "CTR-"+grant.taskNo(),grant.ownerId(),grant.deviceId(),grant.taskNo(),TestComputeWorkerService.CLIENT,NOW);
            case "ledger" -> jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(?,?,'COMPUTE_TASK_REWARD','USDT','IN',0.045005,10.045005,'SUCCESS')",grant.ownerId(),grant.taskNo());
            case "foreignMarker" -> jdbc.update("UPDATE nx_user_device_runtime SET agent_version='foreign-marker'");
            case "foreignClient" -> jdbc.update("UPDATE nx_user_device_runtime SET client_name='foreign-client'");
            case "commercialTask" -> jdbc.update("UPDATE nx_compute_task SET model_name='commercial-model',client_name='UVEL App'");
            case "foreignOwner" -> jdbc.update("UPDATE nx_user_device SET user_id=8");
            case "foreignInstance" -> jdbc.update("UPDATE nx_user_device SET instance_no='FOREIGN-INSTANCE'");
            case "foreignConfig" -> jdbc.update("UPDATE nx_compute_task SET task_config_id='FOREIGN-CONFIG'");
            case "foreignRuntimeTask" -> jdbc.update("UPDATE nx_user_device_runtime SET active_task_no='CTA-FOREIGN'");
            case "unownedDevice" -> jdbc.update("UPDATE nx_user_device SET ownership_status='TRANSFERRED'");
            case "otherLedger" -> jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(?,?,'WITHDRAW','USDT','OUT',0.045005,10.045005,'SUCCESS')",grant.ownerId(),grant.taskNo());
        }
        var beforeTask=jdbc.queryForMap("SELECT * FROM nx_compute_task");
        var beforeRuntime=jdbc.queryForMap("SELECT * FROM nx_user_device_runtime");
        int receipts=count("nx_compute_receipt"),ledger=count("nx_wallet_ledger");
        assertThatThrownBy(() -> app.continuousWorkerRelease(continuousIdentity(),grant.taskNo(),fixed("RELEASE")))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_compute_task")).isEqualTo(beforeTask);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_user_device_runtime")).isEqualTo(beforeRuntime);
        assertBalances("10","20");
        assertThat(count("nx_compute_receipt")).isEqualTo(receipts); assertThat(count("nx_wallet_ledger")).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='TEST_COMPUTE_WORKER_RELEASED'",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"cancelTestWorkerAssignment","closeTestWorkerRuntime","clearRuntimeTask","audit"})
    void continuousReleaseCasOrAuditFailureRollsBackCancellationAndRuntime(String point) {
        continuousFixture();
        var beforeTask=jdbc.queryForMap("SELECT * FROM nx_compute_task");
        var beforeRuntime=jdbc.queryForMap("SELECT * FROM nx_user_device_runtime");
        failure=point;
        assertThatThrownBy(() -> app.continuousWorkerRelease(continuousIdentity(),grant.taskNo(),fixed("RELEASE")))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_compute_task")).isEqualTo(beforeTask);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_user_device_runtime")).isEqualTo(beforeRuntime);
        assertNoRewards();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='TEST_COMPUTE_WORKER_RELEASED'",Integer.class)).isZero();
    }

    @Test void continuousReleaseCannotCancelAnAlreadySettledTaskOrChangeItsActualFunds() {
        continuousFixture();
        app.continuousWorkerComplete(continuousIdentity(),grant.taskNo(),"v2-before-release",result());
        var beforeTask=jdbc.queryForMap("SELECT * FROM nx_compute_task");
        assertThatThrownBy(() -> app.continuousWorkerRelease(continuousIdentity(),grant.taskNo(),fixed("RELEASE")))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_compute_task")).isEqualTo(beforeTask);
        assertBalances("10.045005","23");
        assertThat(count("nx_compute_receipt")).isEqualTo(1); assertThat(count("nx_wallet_ledger")).isEqualTo(2);
    }

    @Test void continuousCompleteVersusReleaseRaceHasOnlySettlementOrCancellation() throws Exception {
        continuousFixture(); var request=result();
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results=new ArrayList<>();
            for(boolean cancel:List.of(false,true)) results.add(pool.submit(() -> {
                ready.countDown(); if(!start.await(5,TimeUnit.SECONDS)) throw new AssertionError("local start timeout");
                try { return cancel ? app.continuousWorkerRelease(continuousIdentity(),grant.taskNo(),fixed("RELEASE"))
                        : app.continuousWorkerComplete(continuousIdentity(),grant.taskNo(),"v2-release-race",request); }
                catch(RuntimeException failed) { return failed; }
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            for(var result:results) result.get(15,TimeUnit.SECONDS);
            String status=jdbc.queryForObject("SELECT status FROM nx_compute_task",String.class);
            assertThat(status).isIn("CANCELLED","COMPLETED");
            boolean settled=status.equals("COMPLETED");
            assertThat(count("nx_compute_receipt")).isEqualTo(settled ? 1 : 0);
            assertThat(count("nx_wallet_ledger")).isEqualTo(settled ? 2 : 0);
            assertBalances(settled ? "10.045005" : "10",settled ? "23" : "20");
            assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime",String.class)).isNull();
        } finally { pool.shutdownNow(); }
    }

    private ContinuousIdentity continuousIdentity() { return new ContinuousIdentity("test355-continuous"); }

    private void continuousFixture() {
        jdbc.update("UPDATE nx_user SET id=? WHERE id=7",TestComputeWorkerService.CONTINUOUS_OWNER);
        jdbc.update("UPDATE nx_order SET user_id=?",TestComputeWorkerService.CONTINUOUS_OWNER);
        jdbc.update("UPDATE nx_user_wallet SET user_id=?",TestComputeWorkerService.CONTINUOUS_OWNER);
        jdbc.update("UPDATE nx_user_device SET id=?,user_id=?,instance_no=?,vram_total_gb=0",TestComputeWorkerService.CONTINUOUS_DEVICE,TestComputeWorkerService.CONTINUOUS_OWNER,TestComputeWorkerService.CONTINUOUS_INSTANCE);
        jdbc.update("UPDATE nx_user_device_runtime SET user_device_id=?",TestComputeWorkerService.CONTINUOUS_DEVICE);
        jdbc.update("UPDATE nx_admin_device_task SET task_id=?,min_reward=0.045005,max_reward=0.045005",TestComputeWorkerService.CONTINUOUS_CONFIG);
        jdbc.update("UPDATE nx_compute_task SET user_id=?,user_device_id=?,task_config_id=?,reward_usdt=0.045005",TestComputeWorkerService.CONTINUOUS_OWNER,TestComputeWorkerService.CONTINUOUS_DEVICE,TestComputeWorkerService.CONTINUOUS_CONFIG);
        configure("CTA-TEST-ONE",true);
        app.continuousWorkerNext(continuousIdentity(),"TEST355-NEXT-"+UUID.randomUUID().toString().replace("-",""));
        grant=worker.continuousTaskGrant(continuousIdentity(),"CTA-TEST-ONE");
    }

    private ffdd.opsconsole.shared.api.ApiResult<Map<String,Object>> claim() { return app.testWorkerClaim(grant,grant.taskNo(),fixed("CLAIM")); }
    private String fixed(String op) { return "TEST355-"+op+"-"+TestComputeWorkerService.sha256(grant.taskNo().getBytes(StandardCharsets.UTF_8)); }
    private CompleteRequest result() {
        var task=mapper.lockAssignment(grant.ownerId(),grant.taskNo(),"PRODUCTION");
        var input=worker.input(grant,task); byte[] bytes=worker.expectedResult(input);
        // The fixed original fixture matches an independently executed Python oracle, not just Java self-comparison.
        if(!grant.continuous() && grant.taskNo().equals("CTA-TEST-ONE")) assertThat(TestComputeWorkerService.sha256(bytes)).isEqualTo("08848de76d730b47d5cde8e594d6f58e0a6eae32dd3200ffa4206a5c8df621cc");
        return new CompleteRequest(TestComputeWorkerService.SPEC,input.hash(),TestComputeWorkerService.sha256(bytes),Base64.getEncoder().encodeToString(bytes),task.completionNonce(),INSTANT.toEpochMilli());
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private void assertBalances(String usdt,String nex) {
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo(usdt);
        assertThat(jdbc.queryForObject("SELECT nex_available FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo(nex);
    }
    private void assertNoRewards() { assertBalances("10","20"); for(String table:List.of("nx_compute_receipt","nx_earning_event","nx_event_outbox")) assertThat(count(table)).as(table).isZero(); assertThat(count("nx_wallet_ledger")).isZero(); }

    private void configure(String taskNo) {
        configure(taskNo,false);
    }
    private void configure(String taskNo,boolean continuous) {
        env=continuous ? TestComputeWorkerServiceTest.continuousEnvironment() : TestComputeWorkerServiceTest.environment(taskNo);
        if(!continuous) env.setActiveProfiles("prod");
        MybatisConfiguration config=new MybatisConfiguration(new Environment("bug355-local",new SpringManagedTransactionFactory(),source));
        var global=new GlobalConfig();global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new ffdd.opsconsole.shared.config.MybatisMetaObjectHandler(businessClock));
        GlobalConfigUtils.setGlobalConfig(config,global);
        config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(AppTaskAssignmentMapper.class,AuditLogMapper.class,EventOutboxMapper.class,AdminIdempotencyRecordMapper.class,
                ffdd.opsconsole.finance.mapper.SupportFundJournalMapper.class,
                ffdd.opsconsole.device.mapper.SupportDeviceReadMapper.class)) config.addMapper(type);
        if(phoneMode) for(Class<?> type:List.of(AuthSessionMapper.class,AppLoginAttemptMapper.class,UserOpsMapper.class,
                OnboardingCalibrationMapper.class,PhoneInstallationMapper.class)) config.addMapper(type);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper=controlled(AppTaskAssignmentMapper.class,session.getMapper(AppTaskAssignmentMapper.class));
        var auditMapper=controlled(AuditLogMapper.class,session.getMapper(AuditLogMapper.class));
        var policy=mock(A2RuntimePolicy.class);when(policy.schemaVersion()).thenReturn("v1");
        var audit=new AuditLogService(auditMapper,new AuditLogSanitizer(json),new ApplicationNameProperties(),new AuditProperties(),mock(AdminMapper.class),policy);
        var expiry=transactional(new AdminIdempotencyExpiryTransitionExecutor(session.getMapper(AdminIdempotencyRecordMapper.class)));
        var executor=transactional(new AdminIdempotencyTransactionExecutor(session.getMapper(AdminIdempotencyRecordMapper.class),json,expiry));
        // Idempotency SQL uses MySQL NOW(); the fixed artifact/proof clock must not pre-expire replay receipts.
        LocalDateTime idempotencyNow=jdbc.queryForObject("SELECT NOW()",LocalDateTime.class);
        var idempotency=new AdminIdempotencyService(executor,Clock.fixed(idempotencyNow.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
        var outbox=new EventOutboxService(controlled(EventOutboxMapper.class,session.getMapper(EventOutboxMapper.class)),json,new OutboxProperties(),mock(A4RuntimePolicyService.class));
        var proof=mock(ComputeTaskProofVerifier.class);when(proof.sourceEnvironment()).thenReturn("PRODUCTION");
        ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract phone=mock(ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract.class);
        if(phoneMode) {
            proof=new ComputeTaskProofVerifier("PRODUCTION","phone-executor:NEX-TEST-INSTANCE:"
                    +Base64.getEncoder().encodeToString(PHONE_PROOF_KEY),120000,businessClock,env);
            var platform=mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class);
            when(platform.activeValue(anyString())).thenReturn(Optional.empty());
            var users=session.getMapper(UserOpsMapper.class);
            var blocklist=mock(UserAccountBlocklistVerifier.class);
            var current=transactional(new CurrentAppSessionService(session.getMapper(AuthSessionMapper.class),platform,blocklist,users,env));
            phone=transactional(new OnboardingCalibrationService(session.getMapper(OnboardingCalibrationMapper.class),
                    mock(ffdd.opsconsole.growth.application.WheelSandboxProfile.class),env,audit,outbox,platform,businessClock,
                    new PhoneInstallationService(session.getMapper(PhoneInstallationMapper.class),current),
                    mock(ffdd.opsconsole.onboarding.application.PhoneReplacementService.class),
                    mock(ffdd.opsconsole.common.boundary.PhoneActivationCapacityContract.class),
                    mock(ffdd.opsconsole.common.boundary.UserTermsAcceptanceContract.class)));
            appSessions=new AppSingleSessionService(session.getMapper(AuthSessionMapper.class),session.getMapper(AppLoginAttemptMapper.class),
                    new JwtTokenProvider(new JwtProperties()),phone,audit,outbox,platform,users,blocklist,env);
        }
        worker=transactional(new TestComputeWorkerService(mapper,audit,businessClock,env));
        var target=new AppTaskAssignmentService(mapper,idempotency,outbox,audit,proof,env,businessClock,worker,
            new SupportDeviceFundAdapter(new ffdd.opsconsole.finance.application.SupportFundJournalBindingService(
                session.getMapper(ffdd.opsconsole.finance.mapper.SupportFundJournalMapper.class),source,json),
                session.getMapper(ffdd.opsconsole.device.mapper.SupportDeviceReadMapper.class),
                new SupportDeviceReadService(session.getMapper(ffdd.opsconsole.device.mapper.SupportDeviceReadMapper.class)),json),phone);
        app=transactional(target);
        grant=continuous ? worker.continuousGrant(continuousIdentity(),taskNo,INSTANT.minusSeconds(10).toEpochMilli(),INSTANT.plusSeconds(86400).toEpochMilli())
                : worker.authenticate("Bearer "+TestComputeWorkerServiceTest.TOKEN);
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
                "nx_admin_idempotency_record","nx_audit_log","nx_event_outbox","nx_event_schema_registry","nx_event_schema_property",
                "nx_test_cloud_execution","nx_test_cloud_binding")) {
            var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+" \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(sql);
            assertThat(matcher.find()).as("formal schema DDL "+table).isTrue();
            String definition=matcher.group();
            if(table.equals("nx_user_wallet")) {
                // UUID identity belongs in the fresh table: ALTER ADD with a system-function default is unsafe.
                definition=definition.replace("nx_user_wallet (", "nx_user_wallet (\n"
                        + "  fund_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT (UUID()),");
            }
            jdbc.execute(definition);
        }
        // Existing canonical migration; schema.sql predates wallet sandbox. No production schema change.
        jdbc.execute("ALTER TABLE nx_user_wallet ADD COLUMN sandbox TINYINT(1) NOT NULL DEFAULT 0");
        createFundJournalDependencies();
        // Minimal legacy E2 projection, using only actual AppTaskAssignmentMapper's current columns.
        jdbc.execute("CREATE TABLE nx_admin_device_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(64) UNIQUE,name VARCHAR(128),task_class VARCHAR(32),model_name VARCHAR(128),min_reward DECIMAL(18,6),max_reward DECIMAL(18,6),min_vram VARCHAR(32),status VARCHAR(32),kill_init VARCHAR(32),updated_at DATETIME,is_deleted TINYINT DEFAULT 0)");
        String lifecycle=Files.readString(Path.of("scripts/migrations/20260810_ab_pending_closure.sql"));
        var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS nx_admin_event_lifecycle \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(lifecycle);
        assertThat(matcher.find()).isTrue();jdbc.execute(matcher.group());
    }

    private void createFundJournalDependencies() throws Exception {
        // The real settlement adapter binds wallet revisions; reuse canonical table/trigger bodies.
        // This fixture exercises writers and rollback, not the migration deployment integrity guards.
        jdbc.execute("ALTER TABLE nx_user_wallet ADD cregis_risk_held DECIMAL(18,6) NOT NULL DEFAULT 0,"
                + "ADD earnings_usdt_debited DECIMAL(30,6) NOT NULL DEFAULT 0,"
                + "ADD earnings_nex_debited DECIMAL(30,6) NOT NULL DEFAULT 0,"
                + "ADD fund_revision BIGINT NOT NULL DEFAULT 0");
        String earnings=Files.readString(Path.of("scripts/migrations/20261007_earnings_source_recovery.sql"));
        var debit=Pattern.compile("CREATE TRIGGER IF NOT EXISTS nx_wallet_earnings_debit_counter_v1.*?;",Pattern.DOTALL).matcher(earnings);
        assertThat(debit.find()).isTrue();jdbc.execute(debit.group());
        for(String file:List.of("20261011_support_fund_journal.sql","20261011_support_fund_operations.sql",
                "20261011_support_fund_operation_party.sql")) {
            String migration=Files.readString(Path.of("scripts/migrations",file));
            var tables=Pattern.compile("CREATE TABLE IF NOT EXISTS nx_support_[a-z_]+ \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(migration);
            int tableCount=0;
            while(tables.find()){jdbc.execute(tables.group());tableCount++;}
            assertThat(tableCount).as(file+" canonical tables").isPositive();
            boolean compound=file.equals("20261011_support_fund_journal.sql");
            var triggers=Pattern.compile(compound ? "CREATE TRIGGER IF NOT EXISTS .*?END\\$\\$"
                    : "CREATE TRIGGER IF NOT EXISTS .*?;",Pattern.DOTALL).matcher(migration);
            int triggerCount=0;
            while(triggers.find()){
                String trigger=triggers.group();jdbc.execute(compound ? trigger.substring(0,trigger.length()-2) : trigger);
                triggerCount++;
            }
            assertThat(triggerCount).as(file+" canonical triggers").isPositive();
        }
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
