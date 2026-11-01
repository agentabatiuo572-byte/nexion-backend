package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import ffdd.opsconsole.device.mapper.LegacyZeroCreditResourceSchema;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;

/** Production MyBatis resource predicates against an owned disposable schema; no business model or money is synthesized. */
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestCloudV2ResourceAdmissionMySqlTest {
    @TempDir Path directory;
    private Connection connection;
    private JdbcTemplate jdbc;
    private String database;
    private TestCloudRequestMapper mapper;
    private Configuration mybatis;
    private TransactionTemplate transactions;
    private Set<Long> logical;
    private TestCloudActivationAdmission admission;
    private ComputeV2Runtime runtime;

    @BeforeAll void initialize() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_URL");
        assertThat(url).matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]*(?:\\?.*)?$");
        connection=DriverManager.getConnection(url,System.getenv("NEXION_TEST_DB_USERNAME"),System.getenv("NEXION_TEST_DB_PASSWORD"));
        var source=new SingleConnectionDataSource(connection,true); jdbc=new JdbcTemplate(source);
        database="compute_v2_capacity_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4"); jdbc.execute("USE "+database);
        jdbc.execute("CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,is_deleted INT,source_environment VARCHAR(16),run_id VARCHAR(32),ownership_status VARCHAR(16),device_type VARCHAR(16),activated_at DATETIME,deactivated_at DATETIME,status VARCHAR(16),pending_deactivate INT)");
        LegacyZeroCreditResourceSchema.requests(jdbc);
        jdbc.execute("ALTER TABLE nx_test_cloud_request ADD proof_validated TINYINT DEFAULT 0, ADD reviewed_termination TINYINT DEFAULT 0, ADD response_json JSON");
        LegacyZeroCreditResourceSchema.resolutions(jdbc);
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_test_cloud_execution","nx_compute_receipt")) {
            var ddl=java.util.regex.Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+" \\(.*?\\) ENGINE=InnoDB[^\\r\\n]*;").matcher(schema);
            assertThat(ddl.find()).as(table).isTrue();jdbc.execute(ddl.group());
        }
        for(int run=0;run<2;run++) ScriptUtils.executeSqlScript(connection,new FileSystemResource("scripts/migrations/20261101_compute_v2_resource_index.sql"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_test_cloud_request' AND index_name='idx_state_device_owner'",Integer.class)).isEqualTo(3);
        jdbc.execute("CREATE TABLE nx_compute_task(task_no VARCHAR(96),user_device_id BIGINT,is_deleted INT,source_environment VARCHAR(16),status VARCHAR(16),user_id BIGINT,task_config_id VARCHAR(64),completed_at DATETIME,KEY idx_active(status,user_device_id))");
        jdbc.execute("CREATE TABLE nx_test_cloud_resource_guard(id INT PRIMARY KEY)"); jdbc.execute("INSERT INTO nx_test_cloud_resource_guard VALUES(1)");
        mybatis=new Configuration(new Environment("compute-v2-capacity",new SpringManagedTransactionFactory(),source));mybatis.addMapper(TestCloudRequestMapper.class);
        mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(mybatis)).getMapper(TestCloudRequestMapper.class);
        transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        logical=LongStream.rangeClosed(1,6000).boxed().collect(Collectors.toCollection(LinkedHashSet::new));
    }
    @BeforeEach void devices() {
        for(String table:List.of("nx_user_device","nx_test_cloud_request","nx_compute_task","nx_test_cloud_zero_credit_resolution")) jdbc.execute("TRUNCATE TABLE "+table);
        jdbc.execute("INSERT INTO nx_user_device VALUES "+logical.stream().map(id->"("+id+",0,'PRODUCTION','','OWNED','DEVICE',NULL,NULL,'INVENTORY',0)").collect(Collectors.joining(",")));
        var env=TestCloudExecutorServiceTest.environment();env.setProperty("nexion.compute-v2.enabled","true");env.setProperty("nexion.compute-v2.deployment-scope","TEST");
        env.setProperty("nexion.compute-v2.device-ids",logical.stream().map(Object::toString).collect(Collectors.joining(",")));
        runtime=mock(ComputeV2Runtime.class);when(runtime.isReady()).thenReturn(true);
        admission=new TestCloudActivationAdmission(mapper,env,Clock.fixed(Instant.ofEpochSecond(1000),ZoneOffset.UTC),Optional.of(runtime));
    }
    @AfterAll void cleanup() throws Exception {
        assertThat(database).matches("compute_v2_capacity_[a-f0-9]{32}");jdbc.execute("DROP DATABASE "+database);connection.close();
    }
    private Path host() throws Exception {
        long physical=32L*1024*1024*1024;
        Path file=directory.resolve("capacity.json");
        Files.writeString(file,"{\"version\":1,\"observedAtEpochSeconds\":1000,\"expiresAtEpochSeconds\":1015,\"maxActiveExecutors\":6,\"allowNewActivations\":true,\"draining\":false,\"physicalMemoryBytes\":"+physical
                +",\"availableMemoryBytes\":"+(10L*1024*1024*1024)+",\"reserveBytes\":"+((physical+4)/5)+",\"committedBytes\":"+(24L*1024*1024*1024)+",\"outstandingBuildBytes\":0}");return file;
    }
    @Test void sixThousandLogicalActivationsAndRestoresDoNotReserveSixThousandModels() {
        transactions.execute(status->{
            admission.lock();
            for(long id:logical) admission.requireActivationCapacity(directory.resolve("missing"),id);
            jdbc.update("UPDATE nx_user_device SET activated_at=CURRENT_TIMESTAMP,status='ACTIVE'");
            assertThat(mapper.activeResourceExecutors()).isEqualTo(6000);
            assertThat(mapper.activeV1ResourceExecutors(logical)).isZero();
            jdbc.update("UPDATE nx_user_device SET deactivated_at=CURRENT_TIMESTAMP,status='DEACTIVATED'");
            for(long id:logical) admission.requireRestoreCapacity(directory.resolve("missing"),id);
            assertThat(mapper.activeV1ResourceExecutors(logical)).isZero();return null;
        });
    }
    @Test void oldUnknownAndProcessingRetainFourPromisesEvenWhenEnrolledOrDeactivated() throws Exception {
        jdbc.update("UPDATE nx_user_device SET activated_at=CURRENT_TIMESTAMP,status='ACTIVE'");
        jdbc.update("INSERT INTO nx_test_cloud_request(device_id,owner_id,state) VALUES(1,7,'UNKNOWN'),(2,7,'UNKNOWN'),(3,7,'PROCESSING'),(4,7,'UNKNOWN'),(5,7,'COMMITTED')");
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_device_id,is_deleted,source_environment,status) VALUES('CTA-old',1,0,'PRODUCTION','RUNNING'),('CV2-current',6,0,'PRODUCTION','RUNNING')");
        assertThat(mapper.activeV1ResourceExecutors(logical)).isEqualTo(4);
        assertThat(mapper.v1ResourceExecutorCount(1,logical)).isZero();
        assertThat(admission.canStartV2Execution(host(),0)).isTrue();assertThat(admission.canStartV2Execution(host(),1)).isTrue();
        assertThat(admission.canStartV2Execution(host(),2)).isFalse();
        jdbc.update("UPDATE nx_user_device SET deactivated_at=CURRENT_TIMESTAMP,status='DEACTIVATED' WHERE id<=4");
        assertThat(mapper.activeV1ResourceExecutors(logical)).isEqualTo(4);
        jdbc.update("UPDATE nx_test_cloud_request SET state='COMMITTED' WHERE device_id=1");
        assertThat(mapper.activeV1ResourceExecutors(logical)).isEqualTo(4); // The original running task still reserves that device.
        jdbc.update("UPDATE nx_compute_task SET status='COMPLETED' WHERE task_no='CTA-old'");
        assertThat(mapper.activeV1ResourceExecutors(logical)).isEqualTo(3);
    }
    @Test void v1AdmissionSeesActualV2PermitsAndFiltersLogicalRowsInsideSql() throws Exception {
        jdbc.update("UPDATE nx_user_device SET activated_at=CURRENT_TIMESTAMP,status='ACTIVE'");
        jdbc.execute("INSERT INTO nx_user_device VALUES(7001,0,'PRODUCTION','','OWNED','DEVICE',CURRENT_TIMESTAMP,NULL,'ACTIVE',0),(7002,0,'PRODUCTION','','OWNED','DEVICE',CURRENT_TIMESTAMP,NULL,'ACTIVE',0),(7003,0,'PRODUCTION','','OWNED','DEVICE',CURRENT_TIMESTAMP,NULL,'OFFLINE',1),(7004,0,'PRODUCTION','','OWNED','DEVICE',CURRENT_TIMESTAMP,NULL,'ACTIVE',0)");
        assertThat(mapper.activeResourceExecutors()).isEqualTo(6004);assertThat(mapper.activeV1ResourceExecutors(logical)).isEqualTo(4);
        Path snapshot=host();when(runtime.activeExecutionCount()).thenReturn(2);
        transactions.execute(status->{admission.lock();assertThatThrownBy(()->admission.requireCapacity(snapshot,1)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");return null;});
        when(runtime.activeExecutionCount()).thenReturn(1);
        transactions.execute(status->{admission.lock();admission.requireCapacity(snapshot,1);return null;});
        assertThat(mapper.v1ResourceExecutorCount(7001,logical)).isEqualTo(1);
        jdbc.update("INSERT INTO nx_test_cloud_request(device_id,owner_id,state) VALUES(7001,7,'UNKNOWN')");
        assertThat(mapper.v1ResourceExecutorCount(7001,logical)).isZero();
        assertThat(mapper.activeV1ResourceExecutors(logical)).isEqualTo(4);
    }
    @Test void resourceCounterUsesStateIndexInsteadOfScanningCommittedHistoryPerLogicalDevice() throws Exception {
        jdbc.update("UPDATE nx_user_device SET activated_at=CURRENT_TIMESTAMP,status='ACTIVE'");
        jdbc.execute("INSERT INTO nx_test_cloud_request(device_id,owner_id,state) VALUES "+IntStream.range(0,24000).mapToObj(n->"("+(n%6000+1)+",7,'COMMITTED')").collect(Collectors.joining(",")));
        jdbc.update("INSERT INTO nx_test_cloud_request(device_id,owner_id,state) VALUES(1,7,'UNKNOWN'),(2,7,'UNKNOWN'),(3,7,'PROCESSING'),(4,7,'UNKNOWN')");
        jdbc.execute("ANALYZE TABLE nx_test_cloud_request");
        assertThat(mapper.activeV1ResourceExecutors(logical)).isEqualTo(4);
        var bound=mybatis.getMappedStatement(TestCloudRequestMapper.class.getName()+".activeV1ResourceExecutors").getBoundSql(Map.of("logicalDevices",logical));
        boolean stateRange=false;
        try(var query=connection.prepareStatement("EXPLAIN "+bound.getSql())) {
            for(int i=0;i<bound.getParameterMappings().size();i++) query.setObject(i+1,bound.getAdditionalParameter(bound.getParameterMappings().get(i).getProperty()));
            try(var plan=query.executeQuery()) {
                while(plan.next()) if("r".equals(plan.getString("table"))) {
                    assertThat(plan.getString("key")).isEqualTo("idx_state_device_owner");
                    assertThat(plan.getString("type")).isIn("range","ref");stateRange=true;
                }
            }
        }
        assertThat(stateRange).isTrue();
    }
}
