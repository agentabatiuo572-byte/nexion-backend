package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.device.application.LegacyUnknownZeroCreditCases;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Actual production SQL over an owned local schema; no business requests or earnings are created. */
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyZeroCreditResourceMySqlTest {
    private static final long OWNER=60723153007L;
    private static final Set<Long> DEVICES=Set.of(1208L,1209L,1212L,1214L);
    private Connection connection;
    private JdbcTemplate jdbc;
    private String database,url,username,password;
    private TestCloudRequestMapper resources;
    private ComputeV2AdmissionMapper admission;
    private ComputeV2ReadMapper reads;

    @BeforeAll void initialize() throws Exception {
        url=System.getenv("NEXION_TEST_DB_URL");username=System.getenv("NEXION_TEST_DB_USERNAME");password=System.getenv("NEXION_TEST_DB_PASSWORD");
        assertThat(url).matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]*(?:\\?.*)?$");
        connection=DriverManager.getConnection(url,username,password);
        var source=new SingleConnectionDataSource(connection,true);jdbc=new JdbcTemplate(source);
        database="compute_v2_zero_resource_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");jdbc.execute("USE "+database);
        jdbc.execute("""
            CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,is_deleted INT,source_environment VARCHAR(16),run_id VARCHAR(32),
              ownership_status VARCHAR(16),device_type VARCHAR(16),activated_at DATETIME,deactivated_at DATETIME,status VARCHAR(16),
              user_id BIGINT,instance_no VARCHAR(128))
            """);
        jdbc.execute("""
            CREATE TABLE nx_compute_task(task_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,
              task_config_id VARCHAR(64),is_deleted INT,source_environment VARCHAR(16),status VARCHAR(16),completed_at DATETIME)
            """);
        LegacyZeroCreditResourceSchema.requests(jdbc);LegacyZeroCreditResourceSchema.resolutions(jdbc);
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(16),is_deleted INT,sandbox INT)");
        jdbc.execute("CREATE TABLE nx_compute_receipt(task_no VARCHAR(96),source_environment VARCHAR(16),is_deleted INT)");
        var config=new Configuration(new Environment("zero-resource",new SpringManagedTransactionFactory(),source));
        config.addMapper(TestCloudRequestMapper.class);config.addMapper(ComputeV2AdmissionMapper.class);config.addMapper(ComputeV2ReadMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        resources=session.getMapper(TestCloudRequestMapper.class);admission=session.getMapper(ComputeV2AdmissionMapper.class);
        reads=session.getMapper(ComputeV2ReadMapper.class);
    }
    @BeforeEach void originalFourRequests() {
        for(String table:List.of("nx_test_cloud_zero_credit_resolution","nx_test_cloud_request","nx_compute_task","nx_user_device","nx_user","nx_compute_receipt"))jdbc.execute("TRUNCATE TABLE "+table);
        jdbc.update("INSERT INTO nx_user VALUES(?,'ACTIVE',0,0)",OWNER);
        for(long device:DEVICES) {
            jdbc.update("INSERT INTO nx_user_device VALUES(?,0,'PRODUCTION','','OWNED','DEVICE',NOW(),NULL,'ACTIVE',?,?)",device,OWNER,"instance-"+device);
            jdbc.update("INSERT INTO nx_compute_task VALUES(?,?,?, ?,0,'PRODUCTION','CANCELLED',NULL)",task(device),OWNER,device,"config-"+device);
            jdbc.update("""
                INSERT INTO nx_test_cloud_request(id,device_id,owner_id,state,executor_id,operation,key_hash,body_hash,
                  instance_no,task_config_id,task_no,generation,recovery_used)
                VALUES(?,?,?,'UNKNOWN','fleet','COMPLETE',?,?,?,?,?,1,1)
                """,device,device,OWNER,hash(device),hash(device+10000),"instance-"+device,"config-"+device,task(device));
        }
    }
    private static String task(long device) {return "CTA-fixture-"+device;}
    private static String hash(long value) {return String.format("%064x",value);}
    private String insertFact(long device) {
        return """
            INSERT INTO nx_test_cloud_zero_credit_resolution(request_id,executor_id,operation,request_key_hash,request_body_hash,
              owner_id,device_id,instance_no,task_config_id,task_no,request_generation,deployment_scope,incident_case_id,outcome,task_status,case_set_sha256)
            SELECT id,executor_id,operation,key_hash,body_hash,owner_id,device_id,instance_no,task_config_id,task_no,generation,
              'TEST',CONCAT('V1-UNKNOWN-20261101-',device_id),'ZERO_CREDIT_CANCELLED','CANCELLED',
            """+"'"+LegacyZeroCreditResolutionSql.CASE_SET_SHA+"'"+"""
            FROM nx_test_cloud_request WHERE id=
            """+device;
    }
    private void assertFour() {
        assertThat(resources.activeV1ResourceExecutors(DEVICES)).isEqualTo(4);
        for(long device:DEVICES) assertThat(admission.unresolvedLegacy(OWNER,device)).isPositive();
    }
    @AfterAll void cleanup() throws Exception {
        assertThat(database).matches("compute_v2_zero_resource_[a-f0-9]{32}");jdbc.execute("DROP DATABASE "+database);connection.close();
    }

    @Test void cancellationAndExpiryWithoutResolutionKeepAllFourOriginalPromises() {
        assertFour();
        jdbc.update("UPDATE nx_user_device SET deactivated_at=NOW(),status='DEACTIVATED'");
        assertFour();
        jdbc.update("UPDATE nx_compute_task SET status='RUNNING'");assertFour();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_request WHERE state='UNKNOWN' AND generation=1 AND recovery_used=1",Integer.class)).isEqualTo(4);
    }
    @Test void onlyExactCommittedFactsReleaseOneAtATimeWithoutEditingOriginalUnknown() {
        assertThat(LegacyZeroCreditResolutionSql.CASE_SET_SHA).isEqualTo(LegacyUnknownZeroCreditCases.MANIFEST_SHA);
        assertFour();int remaining=4;
        for(long device:new TreeSet<>(DEVICES)) {
            jdbc.execute(insertFact(device));remaining--;
            assertThat(resources.activeV1ResourceExecutors(DEVICES)).isEqualTo(remaining);
            assertThat(admission.unresolvedLegacy(OWNER,device)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_request WHERE state='UNKNOWN' AND generation=1 AND recovery_used=1",Integer.class)).isEqualTo(4);
    }
    @Test void everyForeignIdentityAndOutcomeFailsClosed() {
        Map<String,Object> changes=new LinkedHashMap<>();
        changes.put("request_id",99999);changes.put("request_generation",2);changes.put("owner_id",OWNER+1);changes.put("device_id",1209);
        changes.put("executor_id","other");changes.put("operation","NEXT");changes.put("request_key_hash",hash(999));
        changes.put("request_body_hash",hash(998));changes.put("instance_no","foreign-instance");changes.put("task_config_id","foreign-config");
        changes.put("task_no",task(1209));changes.put("deployment_scope","OTHER");changes.put("incident_case_id","V1-UNKNOWN-20261101-1209");
        changes.put("outcome","OTHER");changes.put("task_status","COMPLETED");
        changes.put("case_set_sha256",hash(123456));
        for(var change:changes.entrySet()) {
            jdbc.execute("DELETE FROM nx_test_cloud_zero_credit_resolution");jdbc.execute(insertFact(1208));
            jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET "+change.getKey()+"=?",change.getValue());
            assertFour();
        }
        jdbc.execute("DELETE FROM nx_test_cloud_zero_credit_resolution");jdbc.execute(insertFact(1208));
        jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET task_no=LOWER(task_no)");assertFour();
    }
    @Test void theRealTaskMustStillMatchAndHaveNoCompletion() {
        jdbc.execute(insertFact(1208));
        assertThat(resources.activeV1ResourceExecutors(DEVICES)).isEqualTo(3);
        for(String assignment:List.of("status='RUNNING'","completed_at=NOW()","user_id="+(OWNER+1),"user_device_id=1209",
                "task_config_id='foreign'","is_deleted=1","source_environment='TEST'")) {
            jdbc.execute("UPDATE nx_compute_task SET "+assignment+" WHERE task_no='"+task(1208)+"'");assertFour();
            jdbc.update("UPDATE nx_compute_task SET status='CANCELLED',completed_at=NULL,user_id=?,user_device_id=1208,task_config_id='config-1208',is_deleted=0,source_environment='PRODUCTION' WHERE task_no=?",OWNER,task(1208));
        }
    }
    @Test void uncommittedResolutionDoesNotReleaseAndRollbackDoesNotLeaveAuthority() throws Exception {
        try(var writer=DriverManager.getConnection(url,username,password)) {
            writer.setCatalog(database);writer.setAutoCommit(false);
            try(var statement=writer.createStatement()){statement.executeUpdate(insertFact(1208));}
            assertFour();writer.rollback();assertFour();
            try(var statement=writer.createStatement()){statement.executeUpdate(insertFact(1208));}
            writer.commit();assertThat(resources.activeV1ResourceExecutors(DEVICES)).isEqualTo(3);
            assertThat(admission.unresolvedLegacy(OWNER,1208)).isZero();
        }
    }
    @Test void resolvedCompleteCannotHideAnotherUnknownNextOrNewGeneration() {
        jdbc.execute(insertFact(1208));assertThat(resources.activeV1ResourceExecutors(DEVICES)).isEqualTo(3);
        jdbc.update("INSERT INTO nx_test_cloud_request(id,device_id,owner_id,state,executor_id,operation,key_hash,body_hash,instance_no,task_config_id,task_no,generation) VALUES(9999,1208,?,'UNKNOWN','fleet','NEXT',?,?,?,?,?,1)",
                OWNER,hash(9000),hash(9001),"instance-1208","config-1208",task(1208));assertFour();
        jdbc.update("DELETE FROM nx_test_cloud_request WHERE id=9999");
        jdbc.update("UPDATE nx_test_cloud_request SET generation=2 WHERE id=1208");assertFour();
    }

    @Test void completedResolutionLeavesTheRecoveryReadOnlyForTheOtherUnresolvedCases() {
        var devices=new ArrayList<>(DEVICES);
        assertThat(reads.legacyCompletionRequests(OWNER,devices)).hasSize(4); // CANCELLED alone retains the recovery fact.
        jdbc.execute(insertFact(1208));
        assertThat(reads.legacyCompletionRequests(OWNER,devices)).extracting(ComputeV2ReadMapper.LegacyCompletionRequest::deviceId)
                .containsExactlyInAnyOrder(1209L,1212L,1214L);
        jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET case_set_sha256=?",hash(777));
        assertThat(reads.legacyCompletionRequests(OWNER,devices)).hasSize(4);
        jdbc.update("UPDATE nx_test_cloud_zero_credit_resolution SET case_set_sha256=?",LegacyZeroCreditResolutionSql.CASE_SET_SHA);
        jdbc.update("UPDATE nx_compute_task SET status='RUNNING' WHERE user_device_id=1208");
        assertThat(reads.legacyCompletionRequests(OWNER,devices)).hasSize(4);
        jdbc.update("UPDATE nx_compute_task SET status='CANCELLED' WHERE user_device_id=1208");
        assertThat(reads.legacyCompletionRequests(OWNER,devices)).hasSize(3);
        assertThat(reads.legacyCompletionRequests(OWNER+1,devices)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_request WHERE state='UNKNOWN'",Integer.class)).isEqualTo(4);
    }

    @Test void twoLogicalCanaryScopeKeepsActiveLegacyBaseUntilAllSixAreExplicitlyEnrolled() {
        var two=Set.of(1204L,1210L);
        var six=new HashSet<>(DEVICES);six.addAll(two);
        jdbc.update("""
            INSERT INTO nx_user_device(id,is_deleted,source_environment,run_id,ownership_status,device_type,activated_at,status,user_id,instance_no)
            VALUES(1204,0,'PRODUCTION','','OWNED','DEVICE',NOW(),'ACTIVE',?,'instance-1204'),
                  (1210,0,'PRODUCTION','','OWNED','DEVICE',NOW(),'ACTIVE',?,'instance-1210')
            """,OWNER,OWNER);
        assertThat(resources.activeV1ResourceExecutors(two)).isEqualTo(4);
        assertThat(resources.v1ResourceExecutorCount(1208,two)).isZero(); // UNKNOWN cannot become replacement credit.
        for(long device:DEVICES)jdbc.execute(insertFact(device));
        jdbc.update("UPDATE nx_user_device SET status='DEACTIVATED',deactivated_at=NOW() WHERE id=1209");
        assertThat(resources.activeV1ResourceExecutors(two)).isEqualTo(3); // Three still-active legacy base reservations remain.
        assertThat(resources.v1ResourceExecutorCount(1208,two)).isEqualTo(1);
        assertThat(resources.v1ResourceExecutorCount(1209,two)).isZero();
        assertThat(resources.activeV1ResourceExecutors(six)).isZero();
        assertThat(resources.v1ResourceExecutorCount(1208,six)).isZero(); // A logical device supplies no physical replacement credit.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_test_cloud_request WHERE state='UNKNOWN' AND generation=1",Integer.class)).isEqualTo(4);
    }
}
