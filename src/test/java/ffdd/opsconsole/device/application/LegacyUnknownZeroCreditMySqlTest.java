package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.device.application.LegacyUnknownZeroCreditFixtures.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual source DDL, real mapper SQL and physical Spring/MySQL commits; isolated fixture identities only. */
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyUnknownZeroCreditMySqlTest {
    private static final List<String> FINANCIAL=List.of("nx_compute_receipt","nx_earning_event","nx_wallet_ledger","nx_support_fund_operation");
    private HikariDataSource source;
    private Connection admin;
    private String database;
    private JdbcTemplate jdbc;
    private LegacyUnknownZeroCreditMapper mapper;
    private LegacyUnknownZeroCreditService service;
    private AuditLogService audit;
    private EventOutboxService outbox;
    private TransactionTemplate tx;

    @BeforeAll void schema() throws Exception {
        String original=System.getenv("NEXION_TEST_DB_URL"),user=System.getenv("NEXION_TEST_DB_USERNAME"),password=System.getenv("NEXION_TEST_DB_PASSWORD");
        assertThat(original).matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]+(?:\\?.*)?$");
        database="compute_v2_zero_cancel_"+UUID.randomUUID().toString().replace("-","");
        admin=DriverManager.getConnection(original,user,password);
        admin.createStatement().execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        int slash=original.indexOf('/',"jdbc:mysql://".length()),query=original.indexOf('?',slash);
        source=new HikariDataSource();source.setJdbcUrl(original.substring(0,slash+1)+database+(query<0?"":original.substring(query)));
        source.setUsername(user);source.setPassword(password);source.setMaximumPoolSize(10);source.setMinimumIdle(0);
        source.setTransactionIsolation("TRANSACTION_READ_COMMITTED");jdbc=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_user","nx_user_wallet","nx_user_device","nx_user_device_runtime","nx_compute_task",
                "nx_compute_receipt","nx_earning_event","nx_wallet_ledger","nx_event_outbox","nx_audit_log","nx_audit_log_archive","nx_config_item"))
            createFrom(schema,table);
        createFrom(Files.readString(Path.of("scripts/migrations/20261011_support_fund_operations.sql")),"nx_support_fund_operation");
        String requests=Files.readString(Path.of("scripts/migrations/20261031_test_cloud_request_journal.sql"));
        createFrom(requests,"nx_test_cloud_request");createFrom(requests,"nx_test_cloud_resource_guard");
        jdbc.execute("INSERT INTO nx_test_cloud_resource_guard VALUES(1)");
        createFrom(Files.readString(Path.of("scripts/migrations/20261030_test_cloud_bge_execution.sql")),"nx_test_cloud_execution");
        createFrom(Files.readString(Path.of("scripts/migrations/20261101_test_cloud_zero_credit_resolution.sql")),"nx_test_cloud_zero_credit_resolution");
        mapper=session(source).getMapper(LegacyUnknownZeroCreditMapper.class);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    private void createFrom(String sql,String table) {
        var found=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+" \\(.*?\\) ENGINE=InnoDB[^\\r\\n]*;").matcher(sql);
        assertThat(found.find()).as("actual DDL %s",table).isTrue();jdbc.execute(found.group());
    }
    private SqlSessionTemplate session(DataSource ds) {
        var config=new Configuration(new Environment("zero-cancel",new SpringManagedTransactionFactory(),ds));
        config.addMapper(LegacyUnknownZeroCreditMapper.class);config.addMapper(AppTaskAssignmentMapper.class);config.addMapper(TestCloudRequestMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    }
    private LegacyUnknownZeroCreditService service(DataSource ds,AuditLogService log) {
        var m=session(ds);return new LegacyUnknownZeroCreditService(m.getMapper(LegacyUnknownZeroCreditMapper.class),verifier(),
                m.getMapper(AppTaskAssignmentMapper.class),log,outbox,JSON,environment(),CLOCK);
    }
    @BeforeEach void seed() {
        authenticate();
        for(String t:List.of("nx_test_cloud_zero_credit_resolution","nx_test_cloud_request","nx_test_cloud_execution","nx_compute_task",
                "nx_user_device_runtime","nx_user_device","nx_user_wallet","nx_user","nx_event_outbox","nx_audit_log","nx_audit_log_archive",
                "nx_compute_receipt","nx_earning_event","nx_wallet_ledger","nx_support_fund_operation"))jdbc.execute("DELETE FROM "+t);
        jdbc.update("INSERT INTO nx_user(id,client_ip,password_hash,nickname,referral_code) VALUES(?,'127.0.0.1','fixture','fixture','zero-fixture')",LegacyUnknownZeroCreditCases.OWNER);
        jdbc.update("INSERT INTO nx_user_wallet(user_id) VALUES(?)",LegacyUnknownZeroCreditCases.OWNER);
        for(var c:LegacyUnknownZeroCreditCases.ALL) {
            jdbc.update("INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,status,activated_at) VALUES(?,?,?,'fixture','DEVICE','ACTIVE',NOW())",c.deviceId(),c.ownerId(),c.instanceNo());
            jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,task_config_id,model_name,client_name,status,completion_nonce) VALUES(?,?,?,'EM',?, ?,?,'RUNNING',?)",
                    c.taskNo(),c.ownerId(),c.deviceId(),c.taskConfigId(),TestCloudExecutorService.KIND,TestCloudExecutorService.CLIENT,"e".repeat(64));
            jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,online_status,active_task_no,client_name,agent_version,heartbeat_at) VALUES(?,'OFFLINE',?,?,?,NOW())",
                    c.deviceId(),c.taskNo(),TestCloudExecutorService.CLIENT,"test-cloud:"+c.executorId());
            jdbc.update("INSERT INTO nx_test_cloud_request(executor_id,operation,key_hash,body_hash,owner_id,device_id,instance_no,task_config_id,task_no,state,generation,proof_validated,updated_at) VALUES(?,'COMPLETE',?,?,?,?,?,?,?,'PROCESSING',1,1,NOW())",
                    c.executorId(),c.requestKeyHash(),c.requestBodyHash(),c.ownerId(),c.deviceId(),c.instanceNo(),c.taskConfigId(),c.taskNo());
            jdbc.update("INSERT INTO nx_test_cloud_execution(task_no,executor_id,owner_id,device_id,instance_no,task_config_id,next_key_hash,claim_token,input_json,input_hash,model_tag,model_digest,proof_nonce,created_at) VALUES(?,?,?,?,?,?,?,?,'[]',?,?,?, ?,NOW())",
                    c.taskNo(),c.executorId(),c.ownerId(),c.deviceId(),c.instanceNo(),c.taskConfigId(),c.nextKeyHash(),"f".repeat(64),c.inputHash(),c.modelTag(),c.modelDigest(),"a".repeat(64));
        }
        audit=mock(AuditLogService.class);outbox=mock(EventOutboxService.class);service=service(source,audit);service.register(proof());
    }
    private static void authenticate() {
        var auth=new UsernamePasswordAuthenticationToken("2","fixture",List.of(new SimpleGrantedAuthority("device_e5_write"),new SimpleGrantedAuthority("platform_a2_operation_approve")));
        auth.setDetails(Map.of("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);A2ReplayContext.enterReplay("WO-zero-fixture");
    }
    @AfterEach void clear() { A2ReplayContext.exitReplay();SecurityContextHolder.clearContext(); }
    @AfterAll void close() throws Exception { if(source!=null)source.close();if(admin!=null){admin.createStatement().execute("DROP DATABASE "+database);admin.close();} }
    private LegacyUnknownZeroCreditService.Command command(long device) {
        var c=LegacyUnknownZeroCreditCases.require(device);return new LegacyUnknownZeroCreditService.Command(device,c.caseId(),proof());
    }
    private Map<String,Object> approve(long device) { return tx.execute(s->service.approve(command(device))); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private String requests() { return jdbc.queryForList("SELECT * FROM nx_test_cloud_request ORDER BY id").toString(); }
    private void noCredit() { for(String table:FINANCIAL)assertThat(count(table)).as(table).isZero(); }

    @Test void allFourReallyCancelAndClearOnlyTheirRuntimeWithoutChangingTheOriginalRequestsOrWallet() {
        String before=requests(),wallet=jdbc.queryForList("SELECT * FROM nx_user_wallet").toString();
        for(var c:LegacyUnknownZeroCreditCases.ALL) {
            assertThat(service.candidate(c.deviceId())).containsEntry("eligible",true);
            assertThat(approve(c.deviceId())).containsEntry("outcome","ZERO_CREDIT_CANCELLED").containsEntry("credited",false);
        }
        assertThat(count("nx_test_cloud_zero_credit_resolution")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_compute_task WHERE status='CANCELLED' AND completed_at IS NULL AND proof_consumed_at IS NOT NULL",Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime WHERE active_task_no IS NOT NULL",Long.class)).isZero();
        assertThat(requests()).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM nx_user_wallet").toString()).isEqualTo(wallet);noCredit();
        assertThat(service.candidate(1380)).containsEntry("eligible",false);verify(audit,times(4)).recordRequired(any());
    }
    @Test void everyActualCompletionSideEffectIncludingColdArchiveBlocksCancellation() {
        var c=LegacyUnknownZeroCreditCases.require(1208);String receipt="CTR-"+c.taskNo().substring(4);
        var statements=List.of(
            "INSERT INTO nx_compute_receipt(user_id,user_device_id,task_no,receipt_no,task_type,client_name,proof_hash,completed_at) VALUES("+c.ownerId()+",1208,'"+c.taskNo()+"','"+receipt+"','EM','fixture','hash',NOW())",
            "INSERT INTO nx_earning_event(event_no,user_id,user_device_id,receipt_no,asset,amount,status) VALUES('EARN-"+c.taskNo()+"',"+c.ownerId()+",1208,'"+receipt+"','USDT',1,'POSTED')",
            "INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES("+c.ownerId()+",'"+c.taskNo()+"','COMPUTE_TASK_REWARD','USDT','IN',1,1,'SUCCESS')",
            "INSERT INTO nx_event_outbox(event_id,aggregate_type,aggregate_id,event_type,payload) VALUES('fixture','COMPUTE_TASK','"+c.taskNo()+"','task.completed','{}')",
            "INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id) VALUES('fixture','TEST_CLOUD_RESULT_VERIFIED','COMPUTE_TASK','"+c.taskNo()+"')",
            "INSERT INTO nx_audit_log_archive(original_audit_id,retention_policy_months,expire_at,original_created_at,cold_payload,content_sha256,archived_at) VALUES(1,12,NOW(),NOW(),JSON_OBJECT('action','TASK_ASSIGNMENT_COMPLETED','resourceId','"+c.taskNo()+"'),'"+"a".repeat(64)+"',NOW())",
            "INSERT INTO nx_support_fund_operation(op_key,business_type,phase,source_table,source_key,source_environment,ordered_events,source_snapshot,source_proof,receipt_sha256,protocol_version) VALUES('"+SupportDeviceFundAdapter.key(receipt)+"','SUPPORT_DEVICE_EARNING','CREDIT','nx_compute_receipt','"+receipt+"','PRODUCTION','[]','{\"fixture\":1}','{\"fixture\":1}','"+"a".repeat(64)+"','fixture')");
        for(String sql:statements) {
            jdbc.execute(sql);assertThatThrownBy(()->approve(1208)).hasMessage("LEGACY_UNKNOWN_FINANCIAL_EVIDENCE_EXISTS");
            assertThat(count("nx_test_cloud_zero_credit_resolution")).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,c.taskNo())).isEqualTo("RUNNING");
            for(String table:List.of("nx_compute_receipt","nx_earning_event","nx_wallet_ledger","nx_event_outbox","nx_audit_log","nx_audit_log_archive","nx_support_fund_operation"))jdbc.execute("DELETE FROM "+table);
        }
    }
    @Test void foreignGenerationExecutionInputAndRuntimeCannotBeCancelled() {
        var c=LegacyUnknownZeroCreditCases.require(1208);
        for(String mutation:List.of("UPDATE nx_test_cloud_request SET generation=2 WHERE device_id=1208",
                "UPDATE nx_test_cloud_request SET body_hash=REPEAT('a',64) WHERE device_id=1208",
                "UPDATE nx_test_cloud_execution SET input_hash=REPEAT('a',64) WHERE device_id=1208",
                "UPDATE nx_test_cloud_execution SET next_key_hash=REPEAT('b',64) WHERE device_id=1208",
                "UPDATE nx_user_device_runtime SET active_task_no='foreign' WHERE user_device_id=1208")) {
            jdbc.execute(mutation);assertThatThrownBy(()->approve(1208)).isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
            assertThat(count("nx_test_cloud_zero_credit_resolution")).isZero();seed();
        }
        noCredit();
    }
    @Test void auditAndSqlFailuresRollBackTaskRuntimeResolutionAndPendingDeactivation() {
        jdbc.update("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=1209");
        doThrow(new IllegalStateException("INJECTED_AUDIT_FAILURE")).when(audit).recordRequired(any());
        assertThatThrownBy(()->approve(1209)).hasMessage("INJECTED_AUDIT_FAILURE");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=1209",String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime WHERE user_device_id=1209",String.class)).isEqualTo(LegacyUnknownZeroCreditCases.require(1209).taskNo());
        assertThat(count("nx_test_cloud_zero_credit_resolution")).isZero();
        reset(audit);jdbc.execute("ALTER TABLE nx_test_cloud_zero_credit_resolution ADD CONSTRAINT injected_failure CHECK(device_id<>1208)");
        try { assertThatThrownBy(()->approve(1208)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE nx_test_cloud_zero_credit_resolution DROP CHECK injected_failure"); }
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE user_device_id=1208",String.class)).isEqualTo("RUNNING");noCredit();
    }
    @Test void physicalCommitAckLossReturnsTheSameResolutionAfterRestartWithoutRevalidatingAnExpiredProof() {
        var lost=new AtomicBoolean();
        var lossy=new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                Connection physical=super.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                    try { Object result=m.invoke(physical,a);if(m.getName().equals("commit")&&lost.compareAndSet(false,true))throw new SQLException("INJECTED_POST_COMMIT_ACK_LOSS");return result; }
                    catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
        var uncertain=service(lossy,audit);var uncertainTx=new TransactionTemplate(new DataSourceTransactionManager(lossy));
        assertThatThrownBy(()->uncertainTx.execute(s->uncertain.approve(command(1208)))).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(count("nx_test_cloud_zero_credit_resolution")).isEqualTo(1);
        var original=mapper.resolution(LegacyUnknownZeroCreditCases.require(1208).caseId());
        var restartedVerifier=new ffdd.opsconsole.device.infrastructure.LegacyUnknownQuiescenceVerifier(environment(),
                java.time.Clock.fixed(NOW.plusSeconds(172800),java.time.ZoneOffset.UTC),()->BOOT,UUID.randomUUID().toString());
        var restarted=new LegacyUnknownZeroCreditService(mapper,restartedVerifier,session(source).getMapper(AppTaskAssignmentMapper.class),
                audit,outbox,JSON,environment(),CLOCK);
        var result=tx.execute(s->restarted.approve(command(1208)));assertThat(result.get("resolutionId")).isEqualTo(original.get("resolutionId"));
        assertThat(count("nx_test_cloud_zero_credit_resolution")).isEqualTo(1);verify(audit,times(1)).recordRequired(any());noCredit();
    }
    @Test void corruptSameCaseResolutionAndCaseSetCannotBecomeAFalseIdempotentSuccess() {
        approve(1208);
        jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET task_no='foreign' WHERE device_id=1208");
        assertThat(service.candidate(1208)).containsEntry("status","BLOCKED");
        assertThatThrownBy(()->approve(1208)).hasMessage("LEGACY_UNKNOWN_RESOLUTION_INVALID");
        jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET task_no=?,case_set_sha256=REPEAT('a',64) WHERE device_id=1208",LegacyUnknownZeroCreditCases.require(1208).taskNo());
        assertThatThrownBy(()->approve(1208)).hasMessage("LEGACY_UNKNOWN_RESOLUTION_INVALID");noCredit();
    }
    @Test void concurrentApprovalsUseOneResolutionAndOneCancellation() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Map<String,Object>> action=()->{authenticate();try{start.await();return approve(1208);}finally{clear();}};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS).get("resolutionId")).isEqualTo(b.get(15,TimeUnit.SECONDS).get("resolutionId"));
            assertThat(count("nx_test_cloud_zero_credit_resolution")).isEqualTo(1);verify(audit,times(1)).recordRequired(any());noCredit();
        } finally {pool.shutdownNow();}
    }
    @Test void onlyTheOriginalPendingDeactivationIsFinalizedAndZeroAmountDoesNotCreateAReceipt() {
        jdbc.update("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=1209");approve(1209);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=1209",String.class)).isEqualTo("DEACTIVATED");
        assertThat(jdbc.queryForObject("SELECT pending_deactivate FROM nx_user_device WHERE id=1209",Integer.class)).isZero();
        verify(outbox).publishUserEvent(eq("USER_DEVICE"),eq(LegacyUnknownZeroCreditCases.require(1209).instanceNo()),eq("device.deactivated"),anyLong(),anyString(),anyInt(),anyString(),any());noCredit();
    }
    @Test void aDifferentTasksRealDailyUvtLedgerIsPreservedButAnUnattributedOneBlocks() {
        var c=LegacyUnknownZeroCreditCases.require(1208);
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(?,'CLOUD_SHARE_DAILY:1208:2026-11-01','COMPUTE_TASK_REWARD','UVT','IN',1,1,'SUCCESS')",c.ownerId());
        assertThatThrownBy(()->approve(1208)).hasMessage("LEGACY_UNKNOWN_FINANCIAL_EVIDENCE_EXISTS");
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,client_name,status,completed_at) VALUES('OTHER-DAILY',?,1208,'EM','fixture','COMPLETED','2026-11-01 23:00:00')",c.ownerId());
        jdbc.update("INSERT INTO nx_compute_receipt(user_id,user_device_id,task_no,receipt_no,task_type,client_name,proof_hash,reward_nex,earning_status,completed_at) VALUES(?,1208,'OTHER-DAILY','CTR-OTHER','EM','fixture','fixture',1,'CREDITED','2026-11-01 23:00:00')",c.ownerId());
        jdbc.update("UPDATE nx_compute_receipt SET source_environment='SANDBOX' WHERE task_no='OTHER-DAILY'");
        assertThatThrownBy(()->approve(1208)).hasMessage("LEGACY_UNKNOWN_FINANCIAL_EVIDENCE_EXISTS");
        jdbc.update("UPDATE nx_compute_receipt SET source_environment='PRODUCTION' WHERE task_no='OTHER-DAILY'");
        String daily=jdbc.queryForList("SELECT * FROM nx_wallet_ledger").toString();approve(1208);
        assertThat(jdbc.queryForList("SELECT * FROM nx_wallet_ledger").toString()).isEqualTo(daily);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_compute_receipt WHERE task_no=?",Long.class,c.taskNo())).isZero();
    }
    @Test void lateOriginalCompleteRemainsFencedByTheActualUnmodifiedReserveAuthority() {
        var c=LegacyUnknownZeroCreditCases.require(1208);String original=requests();approve(1208);
        var journal=new TestCloudRequests(session(source).getMapper(TestCloudRequestMapper.class),new TestCloudDatabaseGate(source),CLOCK,new DataSourceTransactionManager(source));
        var identity=new TestCloudExecutorService.Identity(c.executorId(),c.ownerId(),c.deviceId(),c.instanceNo(),c.taskConfigId(),c.modelTag(),c.modelDigest());
        String key="BGE-COMPLETE-"+LegacyUnknownZeroCreditCases.sha(c.taskNo());
        assertThatThrownBy(()->journal.execute(identity,"COMPLETE",key,c.requestBodyHash(),c.taskNo(),NOW.toEpochMilli(),null,null,
                ticket->{throw new AssertionError("A resolved UNKNOWN must never enter inference or settlement");}))
                .hasMessage("TEST_CLOUD_REQUEST_PROCESSING");
        assertThat(requests()).isEqualTo(original);noCredit();
    }
    @Test void genericA2ProposalCannotLockACaseWithAnUnregisteredForgedOrForeignEpochProof() {
        var trust=mock(ffdd.opsconsole.content.domain.TrustDisclosureRepository.class);
        var guard=new ffdd.opsconsole.platform.application.AuditReplayBusinessPermissionGuard(trust,
                mock(ffdd.opsconsole.shared.security.AdminOperatorRoleResolver.class),mock(ffdd.opsconsole.emergency.domain.EmergencyControlRepository.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),null,null,null,service);
        var legitimate=service.proposal(1208,LegacyUnknownZeroCreditCases.sha(proof().observation()));
        assertThat(guard.validateProposal(legitimate).getCode()).isZero();
        var foreign=observation();foreign.put("backendInstanceId",UUID.randomUUID().toString());
        var invalidProofs=List.of(new ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof(proof().observation(),"0".repeat(64)),signed(foreign));
        for(var p:invalidProofs) {
            var params=new LinkedHashMap<String,Object>(legitimate.params());params.put("proof",Map.of("observation",p.observation(),"signature",p.signature()));
            assertThat(guard.validateProposal(new ffdd.opsconsole.platform.domain.AuditReplayCommand("E",legitimate.op(),params)).getCode()).isNotZero();
        }
        var unregistered=service(source,audit);
        assertThatThrownBy(()->unregistered.validateProposal(LegacyUnknownZeroCreditService.command(legitimate.params())))
                .hasMessage("LEGACY_UNKNOWN_QUIESCENCE_REQUIRED");
        assertThat(count("nx_test_cloud_zero_credit_resolution")).isZero();noCredit();
    }
}
