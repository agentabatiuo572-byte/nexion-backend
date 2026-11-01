package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper.Request;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL locks and production intent/manifest SQL; Redis permit count is an explicit boundary double. */
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestCloudPhysicalExecutionMySqlTest {
    @TempDir static Path directory;
    private static final String HASH="a".repeat(64),TOKEN="b".repeat(64),KEY="physical-next-key";
    private static final Clock CLOCK=Clock.fixed(Instant.ofEpochSecond(1000),ZoneOffset.UTC);
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private String database,url;
    private TestCloudRequestMapper mapper;
    private TestCloudActivationAdmission admission;
    private TestCloudRequests requests;
    private TransactionTemplate transactions;
    private ComputeV2Runtime runtime;
    private final AtomicInteger permits=new AtomicInteger();
    private Path capacity;

    @BeforeAll void initialize() throws Exception {
        url=System.getenv("NEXION_TEST_DB_URL");
        assertThat(url).matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]*(?:\\?.*)?$");
        database="compute_v2_physical_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,System.getenv("NEXION_TEST_DB_USERNAME"),System.getenv("NEXION_TEST_DB_PASSWORD"));var sql=connection.createStatement()) {
            sql.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");
        }
        source=new HikariDataSource();source.setJdbcUrl(url.replaceFirst("/[^/?]+(?=\\?|$)","/"+database));
        source.setUsername(System.getenv("NEXION_TEST_DB_USERNAME"));source.setPassword(System.getenv("NEXION_TEST_DB_PASSWORD"));
        source.setMaximumPoolSize(10);source.setMinimumIdle(0);jdbc=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_compute_task","nx_compute_receipt","nx_test_cloud_execution")) {
            var ddl=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+" \\(.*?\\) ENGINE=InnoDB[^\\r\\n]*;").matcher(schema);
            assertThat(ddl.find()).as(table).isTrue();jdbc.execute(ddl.group());
        }
        String journal=Files.readString(Path.of("scripts/migrations/20261031_test_cloud_request_journal.sql"));
        jdbc.execute(journal.substring(journal.indexOf("CREATE TABLE IF NOT EXISTS nx_test_cloud_request"),journal.indexOf(";",journal.indexOf("CREATE TABLE IF NOT EXISTS nx_test_cloud_request"))+1));
        LegacyZeroCreditResourceSchema.resolutions(jdbc);
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,instance_no VARCHAR(128),is_deleted INT,source_environment VARCHAR(16),run_id VARCHAR(32),ownership_status VARCHAR(16),device_type VARCHAR(16),activated_at DATETIME,deactivated_at DATETIME,status VARCHAR(16))");
        jdbc.execute("CREATE TABLE nx_test_cloud_resource_guard(id INT PRIMARY KEY)");jdbc.execute("INSERT INTO nx_test_cloud_resource_guard VALUES(1)");
        var configuration=new Configuration(new Environment("physical-test",new SpringManagedTransactionFactory(),source));
        configuration.addMapper(TestCloudRequestMapper.class);
        mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(TestCloudRequestMapper.class);
        var manager=new DataSourceTransactionManager(source);transactions=new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        capacity=directory.resolve("capacity.json");
        runtime=mock(ComputeV2Runtime.class);when(runtime.activeExecutionCount()).thenAnswer(call->permits.get());
        var environment=TestCloudExecutorServiceTest.environment();
        environment.setProperty("nexion.compute-v2.enabled","true");environment.setProperty("nexion.compute-v2.deployment-scope","TEST");
        environment.setProperty("nexion.compute-v2.device-ids","11");
        admission=new TestCloudActivationAdmission(mapper,environment,CLOCK,Optional.of(runtime)) {
            @Override public void requireNextExecution(TestCloudExecutorService.Identity identity,String keyHash,boolean recovering) {
                super.requireNextExecution(capacity,identity,keyHash,recovering);
            }
        };
        requests=new TestCloudRequests(mapper,new TestCloudDatabaseGate(10),CLOCK,manager,admission);
    }
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("nx_test_cloud_zero_credit_resolution","nx_test_cloud_request","nx_test_cloud_execution","nx_compute_receipt","nx_compute_task","nx_user_device","nx_user"))jdbc.execute("TRUNCATE TABLE "+table);
        jdbc.execute("INSERT INTO nx_user VALUES(7,0)");
        for(long device:List.of(11L,12L,13L,1380L))device(device);
        permits.set(0);when(runtime.isReady()).thenReturn(true);host(true,false);
    }
    private void host(boolean allowed,boolean draining) throws Exception {
        long physical=32L*1024*1024*1024;
        Files.writeString(capacity,"{\"version\":1,\"observedAtEpochSeconds\":1000,\"expiresAtEpochSeconds\":1015,\"maxActiveExecutors\":6,\"allowNewActivations\":"+allowed+",\"draining\":"+draining+",\"physicalMemoryBytes\":"+physical+",\"availableMemoryBytes\":"+(10L*1024*1024*1024)+",\"reserveBytes\":"+((physical+4)/5)+",\"committedBytes\":"+(24L*1024*1024*1024)+",\"outstandingBuildBytes\":0}");
    }
    private void device(long device) {jdbc.update("INSERT INTO nx_user_device VALUES(?,7,?,0,'PRODUCTION','','OWNED','DEVICE',NOW(),NULL,'ACTIVE')",device,"instance-"+device);}
    private TestCloudExecutorService.Identity identity(long device) {return new TestCloudExecutorService.Identity("fleet-"+device,7,device,"instance-"+device,"config","bge-m3:567m","sha256:"+HASH);}
    private Request intent(long device,String operation,String state,String task,String keyHash) {
        var i=identity(device);return new Request(i.executorId(),operation,keyHash,HASH,7,device,i.instanceNo(),"config",task,state,1,null,false,null,false,false,null,LocalDateTime.now(CLOCK));
    }
    private void request(long device,String operation,String state,String task,String keyHash) {
        mapper.insertIntent(intent(device,operation,state,task,keyHash));
        jdbc.update("UPDATE nx_test_cloud_request SET state=? WHERE executor_id=? AND operation=? AND key_hash=?",state,identity(device).executorId(),operation,keyHash);
    }
    private void task(long device,String task,String status) {
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,task_config_id,model_name,client_name,status,lease_expires_at,proof_expires_at) VALUES(?,7,?,'EM','config','TEST_BGE_M3_V1','UVEL TEST BGE-M3',?,'2100-01-01','2100-01-01')",task,device,status);
    }
    private void manifest(long device,String task,String keyHash) {
        jdbc.update("INSERT INTO nx_test_cloud_execution(task_no,executor_id,owner_id,device_id,instance_no,task_config_id,next_key_hash,claim_token,input_json,input_hash,model_tag,model_digest,proof_nonce,created_at) VALUES(?,?,7,?,?,'config',?,?,'[]',?,'bge-m3:567m',?,?,NOW())",task,identity(device).executorId(),device,"instance-"+device,keyHash,HASH,HASH,"sha256:"+HASH,HASH);
    }
    private Map<String,Object> newNext(long device,String key) {
        return requests.execute(identity(device),"NEXT",key,HASH,null,null,null,null,ticket->ApiResult.ok(Map.<String,Object>of("reserved",true))).getData();
    }
    @AfterAll void cleanup() throws Exception {
        if(source!=null)source.close();
        if(database!=null)try(var connection=DriverManager.getConnection(url,System.getenv("NEXION_TEST_DB_USERNAME"),System.getenv("NEXION_TEST_DB_PASSWORD"));var sql=connection.createStatement()) {
            assertThat(database).matches("compute_v2_physical_[a-f0-9]{32}");sql.execute("DROP DATABASE "+database);
        }
    }
    @Test void historicalActiveDevicesAndUnmanifestedTasksAreLogicalOnly() {
        for(long id=100;id<134;id++) {device(id);task(id,"logical-"+id,id%2==0?"CLAIMED":"RUNNING");}
        task(11,"CV2-current","RUNNING");
        assertThat(mapper.activeResourceExecutors()).isEqualTo(38);
        assertThat(mapper.activeV1ResourceExecutors(Set.of(11L))).isEqualTo(37);
        assertThat(mapper.actualV1Executions()).isZero();
        assertThat(admission.canStartV2Execution(capacity,5)).isTrue();
    }
    @Test void uncertainRequestsIncludingUnknownNextKeepDistinctPhysicalPromises() {
        request(1380,"NEXT","UNKNOWN",null,HASH);request(11,"COMPLETE","PROCESSING","old",HASH);
        task(11,"old","EXPIRED");manifest(11,"old",HASH);
        assertThat(mapper.actualV1Executions()).isEqualTo(2);
        assertThat(admission.canStartV2Execution(capacity,3)).isTrue();assertThat(admission.canStartV2Execution(capacity,4)).isFalse();
    }
    @Test void oneV2PermitFitsWithFourActualV1ExecutionsButNotFive() {
        for(long id=100;id<105;id++) {device(id);request(id,"NEXT","UNKNOWN",null,HASH);}
        assertThat(mapper.actualV1Executions()).isEqualTo(5);
        assertThat(admission.canStartV2Execution(capacity,1)).isFalse();
        jdbc.update("UPDATE nx_test_cloud_request SET state='COMMITTED' WHERE device_id=104");
        assertThat(mapper.actualV1Executions()).isEqualTo(4);
        assertThat(admission.canStartV2Execution(capacity,1)).isTrue();
    }
    @Test void expiredOrCancelledManifestCannotBeBorrowedWithoutControlledTerminalEvidence() {
        task(11,"old","EXPIRED");manifest(11,"old",HASH);
        assertThat(mapper.actualV1Executions()).isEqualTo(1);
        assertThatThrownBy(()->newNext(11,KEY)).hasMessage("TEST_CLOUD_EXECUTION_UNRESOLVED");
        jdbc.update("UPDATE nx_compute_task SET status='CANCELLED'");
        assertThat(mapper.actualV1Executions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_request",Integer.class)).isZero();
        request(11,"COMPLETE","ROLLED_BACK","old",HASH);
        jdbc.update("UPDATE nx_test_cloud_request SET recovery_used=1,generation=2,proof_validated=1,response_json=JSON_OBJECT('state','ROLLED_BACK','taskNo','old','taskStatus','CANCELLED','executionGeneration',2,'recoveryAllowed',FALSE)");
        assertThat(mapper.actualV1Executions()).isZero();
        jdbc.update("UPDATE nx_test_cloud_request SET response_json=JSON_SET(response_json,'$.taskNo','foreign')");
        assertThat(mapper.actualV1Executions()).isEqualTo(1);
    }
    @Test void completionRequiresAtomicResultMarkersAndMatchingPostedReceiptNotJustTaskStatus() {
        task(11,"old","COMPLETED");manifest(11,"old",HASH);jdbc.update("UPDATE nx_compute_task SET completed_at=NOW()");
        assertThat(mapper.actualV1Executions()).isEqualTo(1);
        jdbc.update("UPDATE nx_test_cloud_execution SET result_artifact=REPEAT('a',12288),result_hash=SHA2(REPEAT('a',12288),256),complete_key_hash=?,complete_body_hash=?",HASH,HASH);
        jdbc.update("INSERT INTO nx_compute_receipt(user_id,user_device_id,task_no,receipt_no,task_type,client_name,earning_status,proof_hash,completed_at) VALUES(7,11,'old','CTR-old','EM','UVEL TEST BGE-M3','PENDING',?,NOW())",HASH);
        assertThat(mapper.actualV1Executions()).isEqualTo(1);
        jdbc.update("UPDATE nx_compute_receipt SET earning_status='CREDITED'");
        assertThat(mapper.actualV1Executions()).isZero();
        jdbc.update("UPDATE nx_test_cloud_execution SET task_no='OLD'");assertThat(mapper.actualV1Executions()).isEqualTo(1);
        jdbc.update("UPDATE nx_test_cloud_execution SET task_no='old'");assertThat(mapper.actualV1Executions()).isZero();
        jdbc.update("UPDATE nx_compute_receipt SET task_no='OLD'");assertThat(mapper.actualV1Executions()).isEqualTo(1);
        jdbc.update("UPDATE nx_compute_receipt SET task_no='old'");assertThat(mapper.actualV1Executions()).isZero();
        jdbc.update("UPDATE nx_compute_receipt SET user_id=8");assertThat(mapper.actualV1Executions()).isEqualTo(1);
    }
    @Test void exactCommittedZeroCreditFactClosesBothRequestAndManifestOnly() {
        device(1208);task(1208,"old","CANCELLED");manifest(1208,"old",HASH);request(1208,"COMPLETE","UNKNOWN","old",HASH);
        jdbc.update("INSERT INTO nx_test_cloud_zero_credit_resolution SELECT id,executor_id,operation,key_hash,body_hash,owner_id,device_id,instance_no,task_config_id,task_no,generation,'TEST','V1-UNKNOWN-20261101-1208','ZERO_CREDIT_CANCELLED','CANCELLED',? FROM nx_test_cloud_request",LegacyZeroCreditResolutionSql.CASE_SET_SHA);
        assertThat(mapper.actualV1Executions()).isZero();
        jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET case_set_sha256=?",TOKEN);assertThat(mapper.actualV1Executions()).isEqualTo(1);
        jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET case_set_sha256=?",LegacyZeroCreditResolutionSql.CASE_SET_SHA);
        request(1208,"NEXT","UNKNOWN",null,TOKEN);assertThat(mapper.actualV1Executions()).isEqualTo(1);
    }
    @Test void firstIntentIsReservedBeforeBusinessAndOnlyItsAtomicManifestReplacesIt() {
        permits.set(5);
        requests.execute(identity(11),"NEXT",KEY,HASH,null,null,null,null,ticket->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(mapper.actualV1Executions()).isEqualTo(1);
            transactions.execute(status->{assertThat(mapper.lockResourceGuard()).isEqualTo(1);return null;});
            assertThat(admission.canStartV2Execution(capacity,permits.get())).isFalse();
            return requests.commit(ticket,()->{task(11,"created","RUNNING");manifest(11,"created",ticket.keyHash());return ApiResult.ok(Map.<String,Object>of("taskNo","created"));});
        });
        assertThat(mapper.actualV1Executions()).isEqualTo(1);
        assertThat(mapper.read("fleet-11","NEXT",TestCloudExecutorService.sha256(KEY)).state()).isEqualTo("COMMITTED");
    }
    @Test void exactCommittedRetrySurvivesMissingHostAndNewNextCannotOversubscribe() throws Exception {
        requests.execute(identity(11),"NEXT",KEY,HASH,null,null,null,null,ticket->requests.commit(ticket,()->ApiResult.ok(Map.<String,Object>of("taskNo","original"))));
        Files.delete(capacity);when(runtime.isReady()).thenReturn(false);
        assertThat(requests.execute(identity(11),"NEXT",KEY,HASH,null,null,null,null,ticket->{throw new AssertionError("must replay");}).getData()).containsEntry("taskNo","original");
        assertThatThrownBy(()->newNext(12,KEY)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_request",Integer.class)).isEqualTo(1);
    }
    @Test void rolledBackRecoveryNeedsNewCapacityUnlessItsExactLiveManifestStillReservesTheSlot() throws Exception {
        request(11,"NEXT","ROLLED_BACK",null,TestCloudExecutorService.sha256(KEY));
        jdbc.update("UPDATE nx_test_cloud_request SET recovery_token=?",TOKEN);permits.set(6);
        assertThatThrownBy(()->requests.execute(identity(11),"NEXT",KEY,HASH,null,null,TOKEN,1L,ticket->ApiResult.ok(Map.of()))).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        assertThat(mapper.read("fleet-11","NEXT",TestCloudExecutorService.sha256(KEY)).state()).isEqualTo("ROLLED_BACK");
        task(11,"original","RUNNING");manifest(11,"original",TestCloudExecutorService.sha256(KEY));
        Files.delete(capacity);when(runtime.isReady()).thenReturn(false);
        assertThat(requests.execute(identity(11),"NEXT",KEY,HASH,null,null,TOKEN,1L,ticket->ApiResult.ok(Map.<String,Object>of("generation",ticket.generation()))).getData()).containsEntry("generation",2L);
        assertThat(mapper.actualV1Executions()).isEqualTo(1);
    }
    @Test void v1IntentAndV2PermitRaceForTheLastPhysicalSlotUnderTheSameMysqlGuard() throws Exception {
        permits.set(5);var start=new CountDownLatch(1);var winners=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var v1=pool.submit(()->{await(start);try {newNext(11,KEY);winners.incrementAndGet();}catch(BizException full){assertThat(full.getMessage()).isEqualTo("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");}});
            var v2=pool.submit(()->{await(start);transactions.execute(status->{admission.lock();if(admission.canStartV2Execution(capacity,permits.get())){permits.incrementAndGet();winners.incrementAndGet();}return null;});});
            start.countDown();v1.get(15,TimeUnit.SECONDS);v2.get(15,TimeUnit.SECONDS);
            assertThat(winners.get()).isEqualTo(1);assertThat(mapper.actualV1Executions()+permits.get()).isEqualTo(6);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void twoNewV1IntentsCannotBothBorrowTheLastSlot() throws Exception {
        permits.set(5);var start=new CountDownLatch(1);var winners=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> attempts=new ArrayList<>();
            for(long device:List.of(11L,12L))attempts.add(pool.submit(()->{await(start);try {newNext(device,KEY);winners.incrementAndGet();}catch(BizException full){assertThat(full.getMessage()).isEqualTo("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");}}));
            start.countDown();for(var attempt:attempts)attempt.get(15,TimeUnit.SECONDS);
            assertThat(winners.get()).isEqualTo(1);assertThat(mapper.actualV1Executions()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_request WHERE state='PROCESSING'",Integer.class)).isEqualTo(1);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void failedBusinessTransactionNeverLeavesAnUnreservedManifestOrAnExtraRecoveryPermit() {
        permits.set(5);
        assertThatThrownBy(()->requests.execute(identity(11),"NEXT",KEY,HASH,null,null,null,null,ticket->requests.commit(ticket,()->{
            task(11,"rolled-back","RUNNING");manifest(11,"rolled-back",ticket.keyHash());throw new IllegalStateException("ROLLBACK_AFTER_MANIFEST");
        }))).hasMessage("ROLLBACK_AFTER_MANIFEST");
        assertThat(mapper.actualV1Executions()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_execution",Integer.class)).isZero();
        Request prior=mapper.read("fleet-11","NEXT",TestCloudExecutorService.sha256(KEY));
        assertThat(prior.state()).isEqualTo("ROLLED_BACK");assertThat(prior.recoveryToken()).isNotNull();
        permits.set(6);
        assertThatThrownBy(()->requests.execute(identity(11),"NEXT",KEY,HASH,null,null,prior.recoveryToken(),prior.generation(),ticket->ApiResult.ok(Map.of()))).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        assertThat(mapper.read("fleet-11","NEXT",prior.keyHash()).generation()).isEqualTo(1);
    }
    private static void await(CountDownLatch latch) {try {assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
}
