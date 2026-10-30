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
        var source=new DriverManagerDataSource(url.substring(0,query<0?url.length():query)+schema
                +(query<0?"":url.substring(query)),user,password);
        jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE it_owner(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_device(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_task(task_no VARCHAR(96) PRIMARY KEY,status VARCHAR(16) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_execution(task_no VARCHAR(96) PRIMARY KEY,result_hash VARCHAR(64),artifact LONGBLOB,"
                +"key_hash VARCHAR(64),body_hash VARCHAR(64)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_receipt(task_no VARCHAR(96) PRIMARY KEY,proof_hash VARCHAR(64) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE it_ledger(task_no VARCHAR(96) PRIMARY KEY) ENGINE=InnoDB");
        setupComplete=true;
    }

    @BeforeEach void resetFixture() throws Exception {
        for(String table:List.of("it_ledger","it_receipt","it_execution","it_task","it_device","it_owner"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO it_owner VALUES(7)"); jdbc.update("INSERT INTO it_device VALUES(11)");
        jdbc.update("INSERT INTO it_task VALUES(?,'RUNNING')",TASK);
        jdbc.update("INSERT INTO it_execution(task_no) VALUES(?)",TASK);
        resultWrites.set(0); settlementCalls.set(0); failSettlement.set(false);
        workers=Executors.newFixedThreadPool(2);
        fixture=new TestCloudExecutorServiceTest(); fixture.setup();
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
            jdbc.update("INSERT INTO it_ledger VALUES(?)",TASK); settlementCalls.incrementAndGet();
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

    @Test void slowInferenceDoesNotHoldTheOwnerLockOnItsRealTransactionConnection() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var inferenceConnection=new AtomicLong();
        doAnswer(call->{ inferenceConnection.set(assertCurrentReadCommittedTransaction()); entered.countDown();
            assertThat(release.await(20,TimeUnit.SECONDS)).isTrue(); return TestCloudBgeArtifactTest.expected(0.25); })
                .when(fixture.service).infer(fixture.identity);
        var request=fixture.request(0.25f);
        var completion=workers.submit(()->service.complete(fixture.identity,TASK,KEY,request));
        try {
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(manager).execute(status->{
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=2");
                assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)).isNotEqualTo(inferenceConnection.get());
                assertThat(jdbc.queryForObject("SELECT id FROM it_owner WHERE id=7 FOR UPDATE",Long.class)).isEqualTo(7L);
                return null;
            });
            assertThat(completion.isDone()).isFalse();
            release.countDown(); assertThat(completion.get(15,TimeUnit.SECONDS).getData()).containsEntry("found",true);
        } finally { release.countDown(); }
    }

    @Test void concurrentSameKeyCompletionReadsTheWinningReceiptAndSettlesOnce() throws Exception {
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        var connections=ConcurrentHashMap.<Long>newKeySet();
        doAnswer(call->{ connections.add(assertCurrentReadCommittedTransaction()); entered.countDown();
            assertThat(release.await(20,TimeUnit.SECONDS)).isTrue(); return TestCloudBgeArtifactTest.expected(0.25); })
                .when(fixture.service).infer(fixture.identity);
        var request=fixture.request(0.25f);
        var first=workers.submit(()->service.complete(fixture.identity,TASK,KEY,request));
        var second=workers.submit(()->service.complete(fixture.identity,TASK,KEY,request));
        try {
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue(); assertThat(connections).hasSize(2); release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).getData()).containsEntry("found",true).containsEntry("status","COMPLETED");
            assertThat(second.get(15,TimeUnit.SECONDS).getData()).containsEntry("found",true).containsEntry("status","COMPLETED");
            assertThat(resultWrites).hasValue(1); assertThat(settlementCalls).hasValue(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_execution WHERE result_hash IS NOT NULL",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_receipt",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_ledger",Integer.class)).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test void settlementFailureRollsBackResultReceiptLedgerAndTaskInTheOuterTransaction() throws Exception {
        failSettlement.set(true);
        doAnswer(call->{ assertCurrentReadCommittedTransaction(); return TestCloudBgeArtifactTest.expected(0.25); })
                .when(fixture.service).infer(fixture.identity);
        var request=fixture.request(0.25f);
        assertThatThrownBy(()->service.complete(fixture.identity,TASK,KEY,request)).isInstanceOf(IllegalStateException.class)
                .hasMessage("ISOLATED_SETTLEMENT_FAILURE");
        assertThat(resultWrites).hasValue(1); assertThat(settlementCalls).hasValue(1);
        Execution persisted=readExecution(false);
        assertThat(persisted.resultHash()).isNull(); assertThat(persisted.resultArtifact()).isNull();
        assertThat(persisted.completeKeyHash()).isNull(); assertThat(persisted.completeBodyHash()).isNull();
        assertThat(readTask(false).status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_receipt",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_ledger",Integer.class)).isZero();
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
