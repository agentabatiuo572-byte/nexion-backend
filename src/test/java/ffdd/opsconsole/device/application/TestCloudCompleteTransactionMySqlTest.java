package ffdd.opsconsole.device.application;

import static ffdd.opsconsole.device.application.TestCloudExecutorServiceTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.AssignmentRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.ReceiptRow;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Execution;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real InnoDB/Spring orchestration using JDBC-backed mapper/settlement doubles in a private schema.
 * Production mapper SQL, model inference and business settlement have separate acceptance tests. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestCloudCompleteTransactionMySqlTest {
    static final String KEY="cloud-complete-mysql";
    Connection admin;
    String schema;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    DriverManagerDataSource source;
    ffdd.opsconsole.device.mapper.TestCloudRequestMapper journal;
    TestCloudRequests requests;
    TestCloudExecutorServiceTest fixture;
    TestCloudExecutorService service;
    ExecutorService workers;
    final AtomicBoolean failSettlement=new AtomicBoolean();
    final AtomicInteger resultWrites=new AtomicInteger(),settlementCalls=new AtomicInteger();
    boolean setupComplete,failed;
    @RegisterExtension final TestWatcher outcome=new TestWatcher() {
        @Override public void testFailed(ExtensionContext context,Throwable cause) { failed=true; }
    };

    @BeforeAll void createIsolatedSchema() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        assertThat(url).as("dedicated loopback MySQL server without a catalog")
                .matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):[0-9]+/([?].*)?");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_cloud_complete_it_"+UUID.randomUUID().toString().replace("-","");
        try(var statement=admin.createStatement()) { statement.execute("CREATE DATABASE `"+schema+"`"); }
        System.out.println("CLOUD_COMPLETE_FIXTURE_CREATE="+schema);
        int query=url.indexOf('?');
        source=new DriverManagerDataSource(url.substring(0,query<0?url.length():query)+schema
                +(query<0?"":url.substring(query)),user,password);
        jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE it_owner(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_device(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_task(task_no VARCHAR(96) PRIMARY KEY,status VARCHAR(16) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_execution(task_no VARCHAR(96) PRIMARY KEY,result_hash VARCHAR(64),artifact LONGBLOB,"
                +"key_hash VARCHAR(64),body_hash VARCHAR(64)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_receipt(task_no VARCHAR(96) PRIMARY KEY,proof_hash VARCHAR(64) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_ledger(task_no VARCHAR(96) PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_wallet(id BIGINT PRIMARY KEY,balance DECIMAL(20,8) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_event(task_no VARCHAR(96) PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_test_cloud_execution(task_no VARCHAR(96) PRIMARY KEY,executor_id VARCHAR(32),next_key_hash CHAR(64)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,is_deleted INT,source_environment VARCHAR(16),run_id VARCHAR(32),"
                +"ownership_status VARCHAR(16),device_type VARCHAR(16),activated_at DATETIME,deactivated_at DATETIME,status VARCHAR(16),pending_deactivate INT) ENGINE=InnoDB");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.FileSystemResource("scripts/migrations/20261031_test_cloud_request_journal.sql")).execute(source);
        var config=new org.apache.ibatis.session.Configuration();
        config.addMapper(ffdd.opsconsole.device.mapper.TestCloudRequestMapper.class);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        journal=new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(ffdd.opsconsole.device.mapper.TestCloudRequestMapper.class);
        requests=new TestCloudRequests(journal,new TestCloudDatabaseGate(4),java.time.Clock.fixed(TestCloudExecutorServiceTest.INSTANT,java.time.ZoneOffset.UTC),manager);
        setupComplete=true;
    }

    @BeforeEach void resetFixture() throws Exception {
        for(String table:List.of("nx_test_cloud_stability_recovery_use","nx_user_device","nx_test_cloud_execution","nx_test_cloud_request","it_event","it_wallet","it_ledger","it_receipt","it_execution","it_task","it_device","it_owner"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO it_owner VALUES(7)"); jdbc.update("INSERT INTO it_wallet VALUES(7,0)"); jdbc.update("INSERT INTO it_device VALUES(11)");
        jdbc.update("INSERT INTO it_task VALUES(?,'RUNNING')",TASK);
        jdbc.update("INSERT INTO it_execution(task_no) VALUES(?)",TASK);
        resultWrites.set(0); settlementCalls.set(0); failSettlement.set(false);
        workers=Executors.newFixedThreadPool(2);
        fixture=new TestCloudExecutorServiceTest(); fixture.setup();
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service,"requests",requests);
        when(fixture.assignments.ownedDevice(7L,11L)).thenAnswer(call->{
            jdbc.queryForObject("SELECT id FROM it_device WHERE id=11",Long.class); return device(); });
        when(fixture.assignments.lockProductionUser(7L)).thenAnswer(call->
                jdbc.queryForObject("SELECT id FROM it_owner WHERE id=7 FOR UPDATE",Long.class));
        when(fixture.assignments.lockOwnedDevice(7L,11L)).thenAnswer(call->{
            jdbc.queryForObject("SELECT id FROM it_device WHERE id=11 FOR UPDATE",Long.class); return device(); });
        when(fixture.assignments.assignment(7L,TASK,"PRODUCTION")).thenAnswer(call->readTask(false));
        when(fixture.assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenAnswer(call->readTask(true));
        when(fixture.mapper.execution(TASK)).thenAnswer(call->readExecution(false));
        when(fixture.mapper.lock(TASK)).thenAnswer(call->readExecution(true));
        when(fixture.mapper.saveResult(eq(TASK),anyString(),any(),anyString(),anyString())).thenAnswer(call->{
            int changed=jdbc.update("UPDATE it_execution SET result_hash=?,artifact=?,key_hash=?,body_hash=? "
                    +"WHERE task_no=? AND result_hash IS NULL AND artifact IS NULL",call.getArgument(1),call.getArgument(2),
                    call.getArgument(3),call.getArgument(4),TASK);
            resultWrites.addAndGet(changed); return changed;
        });
        when(fixture.assignments.receipt(7L,"CTR-"+TASK)).thenAnswer(call->jdbc.query(
                "SELECT proof_hash FROM it_receipt WHERE task_no=?",(row,index)->new ReceiptRow("CTR-"+TASK,TASK,11L,
                        fixture.identity.instanceNo(),"Cloud","SHARE",null,24,"EM-UNIT",TestCloudExecutorService.TASK_NAME,"EM",
                        TestCloudExecutorService.KIND,TestCloudExecutorService.CLIENT,BigDecimal.ONE,BigDecimal.ONE,"CREDITED",
                        row.getString(1),NOW.minusSeconds(10),NOW,10),TASK).stream().findFirst().orElse(null));
        when(fixture.settlement.completeForTestCloud(eq(TASK),any(),any())).thenAnswer(call->{
            var proof=call.getArgument(2,TestCloudExecutorService.CloudProof.class);
            assertThat(jdbc.update("UPDATE it_task SET status='COMPLETED' WHERE task_no=? AND status='RUNNING'",TASK)).isEqualTo(1);
            jdbc.update("INSERT INTO it_receipt VALUES(?,?)",TASK,proof.proofHash());
            jdbc.update("INSERT INTO it_ledger VALUES(?)",TASK);
            jdbc.update("UPDATE it_wallet SET balance=balance+1 WHERE id=7");
            jdbc.update("INSERT INTO it_event VALUES(?)",TASK); settlementCalls.incrementAndGet();
            if(failSettlement.get()) throw new IllegalStateException("ISOLATED_SETTLEMENT_FAILURE");
            return ApiResult.ok(null);
        });
        var proxy=new ProxyFactory(fixture.service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service=(TestCloudExecutorService)proxy.getProxy();
    }

    AssignmentRow readTask(boolean lock) {
        return task(jdbc.queryForObject("SELECT status FROM it_task WHERE task_no=?"+(lock?" FOR UPDATE":""),String.class,TASK));
    }

    Execution readExecution(boolean lock) {
        var base=execution(null,null,null);
        return jdbc.queryForObject("SELECT result_hash,artifact,key_hash,body_hash FROM it_execution WHERE task_no=?"
                +(lock?" FOR UPDATE":""),(row,index)->new Execution(base.taskNo(),base.executorId(),base.ownerId(),base.deviceId(),
                        base.instanceNo(),base.taskConfigId(),base.nextKeyHash(),base.claimToken(),base.inputJson(),base.inputHash(),
                        base.modelTag(),base.modelDigest(),base.proofNonce(),row.getString(1),row.getBytes(2),row.getString(3),
                        row.getString(4),base.createdAt()),TASK);
    }

    long assertCurrentReadCommittedTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
        return jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
    }

    @Test void slowInferenceHoldsNeitherTransactionConnectionNorOwnerLock() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.getResource(source)).isNull();
            entered.countDown(); assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();
            return TestCloudBgeArtifactTest.expected(0.25);
        }).when(fixture.service).infer(fixture.identity);
        var request=fixture.request(0.25f);
        var completion=workers.submit(()->service.complete(fixture.identity,TASK,KEY,request));
        try {
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(manager).execute(status->{
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=2");
                assertThat(jdbc.queryForObject("SELECT id FROM it_owner WHERE id=7 FOR UPDATE",Long.class)).isEqualTo(7L);
                return null;
            });
            assertThat(completion.isDone()).isFalse();
            release.countDown(); assertThat(completion.get(15,TimeUnit.SECONDS).getData()).containsEntry("found",true);
            assertThat(journal.read("cloud-unit","COMPLETE",TestCloudExecutorService.sha256(KEY)).state()).isEqualTo("COMMITTED");
        } finally { release.countDown(); }
    }

    @Test void concurrentSameKeyIsFencedThenCommittedResponseReplaysWithoutAnotherInference() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();
            return TestCloudBgeArtifactTest.expected(0.25);
        }).when(fixture.service).infer(fixture.identity);
        var request=fixture.request(0.25f);
        var first=workers.submit(()->service.complete(fixture.identity,TASK,KEY,request));
        try {
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request)).hasMessage("TEST_CLOUD_REQUEST_PROCESSING");
            release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).getData()).containsEntry("found",true);
            assertThat(service.complete(fixture.identity,TASK,KEY,request).getData()).containsEntry("found",true).containsEntry("status","COMPLETED");
            verify(fixture.service,times(1)).infer(fixture.identity);
            assertThat(resultWrites).hasValue(1); assertThat(settlementCalls).hasValue(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_receipt",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_ledger",Integer.class)).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test void actualRollbackHasOneRecoveryBudgetAndNeverCommitsPartialFinancialEvidence() throws Exception {
        failSettlement.set(true);
        doAnswer(call->{ assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return TestCloudBgeArtifactTest.expected(0.25); }).when(fixture.service).infer(fixture.identity);
        var request=fixture.request(0.25f);
        assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request)).hasMessage("ISOLATED_SETTLEMENT_FAILURE");
        Execution persisted=readExecution(false);
        assertThat(persisted.resultHash()).isNull(); assertThat(persisted.resultArtifact()).isNull();
        assertThat(readTask(false).status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_receipt",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_ledger",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_event",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM it_wallet WHERE id=7",BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
        var rolledBack=journal.read("cloud-unit","COMPLETE",TestCloudExecutorService.sha256(KEY));
        assertThat(rolledBack.state()).isEqualTo("ROLLED_BACK"); assertThat(rolledBack.recoveryToken()).hasSize(64);
        assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request)).hasMessage("TEST_CLOUD_REQUEST_ROLLED_BACK");
        String hash=TestCloudExecutorService.sha256(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request));
        assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request,hash,rolledBack.recoveryToken(),1L))
                .hasMessage("ISOLATED_SETTLEMENT_FAILURE");
        var exhausted=journal.read("cloud-unit","COMPLETE",TestCloudExecutorService.sha256(KEY));
        assertThat(exhausted.state()).isEqualTo("ROLLED_BACK"); assertThat(exhausted.generation()).isEqualTo(2);
        assertThat(exhausted.recoveryUsed()).isTrue(); assertThat(exhausted.recoveryToken()).hasSize(64);
        assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request,hash,rolledBack.recoveryToken(),1L))
                .hasMessage("TEST_CLOUD_REQUEST_ROLLED_BACK");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_ledger",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_event",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM it_wallet WHERE id=7",BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test void globalActivationMutexCountsCurrentCommittedDevicesIncludingPendingDisable() throws Exception {
        jdbc.update("INSERT INTO nx_user_device VALUES(1,0,'PRODUCTION','','OWNED','DEVICE',NULL,NULL,'INVENTORY',0)");
        jdbc.update("INSERT INTO nx_user_device VALUES(2,0,'PRODUCTION','','OWNED','DEVICE',NULL,NULL,'INVENTORY',0)");
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var first=workers.submit(()->requests.db(()->{
            assertThat(journal.lockResourceGuard()).isEqualTo(1); assertThat(journal.activeResourceExecutors()).isZero();
            jdbc.update("UPDATE nx_user_device SET activated_at=CURRENT_TIMESTAMP,status='ACTIVE',pending_deactivate=1 WHERE id=1");
            entered.countDown();
            try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); } catch(InterruptedException failed) { throw new RuntimeException(failed); }
            return true;
        }));
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(()->requests.db(()->{
                assertThat(journal.lockResourceGuard()).isEqualTo(1); return journal.activeResourceExecutors();
            }));
            release.countDown(); assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test void expiredValidatedRollbackHasOneDurableNonFinancialTermination() throws Exception {
        failSettlement.set(true);
        doReturn(TestCloudBgeArtifactTest.expected(0.25)).when(fixture.service).infer(fixture.identity);
        var request=fixture.request(0.25f);
        assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request)).hasMessage("ISOLATED_SETTLEMENT_FAILURE");
        var original=journal.read("cloud-unit","COMPLETE",TestCloudExecutorService.sha256(KEY));
        var expiredClock=java.time.Clock.fixed(TestCloudExecutorServiceTest.INSTANT.plusSeconds(121),java.time.ZoneOffset.UTC);
        var expiredRequests=new TestCloudRequests(journal,new TestCloudDatabaseGate(4),expiredClock,manager);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service,"requests",expiredRequests);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service,"clock",expiredClock);
        when(fixture.mapper.rollbackUnconsumedTask(TASK,7,11)).thenReturn(1);
        when(fixture.mapper.countActive(7,11)).thenReturn(1);
        when(fixture.assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenAnswer(call->readTask(true));
        when(fixture.mapper.cancelRolledBack(eq(TASK),eq(7L),eq(11L),any())).thenAnswer(call->
                jdbc.update("UPDATE it_task SET status='CANCELLED' WHERE task_no=? AND status='RUNNING'",TASK));
        String hash=TestCloudExecutorService.sha256(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request));
        assertThat(service.terminateRequest(fixture.identity,KEY,hash,TASK,original.recoveryToken(),1).getData())
                .containsEntry("taskStatus","CANCELLED").containsEntry("recoveryAllowed",false);
        assertThat(service.terminateRequest(fixture.identity,KEY,hash,TASK,original.recoveryToken(),1).getData())
                .containsEntry("taskStatus","CANCELLED");
        verify(fixture.mapper,times(1)).cancelRolledBack(eq(TASK),eq(7L),eq(11L),any());
        var terminal=journal.read("cloud-unit","COMPLETE",TestCloudExecutorService.sha256(KEY));
        assertThat(terminal.generation()).isEqualTo(2); assertThat(terminal.responseJson()).isNotNull();
        assertThat(readTask(false).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_receipt",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_ledger",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_event",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM it_wallet WHERE id=7",BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request,hash,original.recoveryToken(),1L))
                .hasMessage("TEST_CLOUD_REQUEST_ROLLED_BACK");
    }

    @Test void nextAliasesCannotFabricateDurableTaskCreationProvenance() {
        String key="next-provenance-unit",hash=TestCloudExecutorService.sha256("{}");
        jdbc.update("INSERT INTO nx_test_cloud_execution VALUES(?,?,?)",TASK,"cloud-unit",TestCloudExecutorService.sha256(key));
        requests.execute(fixture.identity,"NEXT",key,hash,null,null,null,null,ticket->requests.commit(ticket,
                ()->ApiResult.ok(java.util.Map.of("taskNo",TASK))));
        assertThat(journal.durableNext("cloud-unit",TASK)).isZero();
        jdbc.update("UPDATE nx_test_cloud_request SET response_json=JSON_OBJECT('taskNo',?,'requestCreatedTask',CAST('true' AS JSON)) WHERE operation='NEXT'",TASK);
        assertThat(journal.durableNext("cloud-unit",TASK)).isEqualTo(1);
        jdbc.update("UPDATE nx_test_cloud_execution SET next_key_hash=? WHERE task_no=?","f".repeat(64),TASK);
        assertThat(journal.durableNext("cloud-unit",TASK)).isZero();
    }

    @AfterEach void stopWorkers() throws Exception {
        if(workers!=null) { workers.shutdownNow(); assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }

    @AfterAll void finishIsolatedSchema() throws Exception {
        if(admin==null) return;
        try {
            if(setupComplete && !failed) {
                try(var statement=admin.createStatement()) { statement.execute("DROP DATABASE `"+schema+"`"); }
                System.out.println("CLOUD_COMPLETE_FIXTURE_DROP="+schema);
            } else System.out.println("CLOUD_COMPLETE_FIXTURE_RETAIN="+schema);
        } finally { admin.close(); }
    }
}
