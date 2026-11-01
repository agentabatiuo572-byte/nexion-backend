package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComputeV2AdmissionMapperMySqlTest {
    private Connection connection;private JdbcTemplate jdbc;private String database;private ComputeV2AdmissionMapper mapper;
    @BeforeAll void initialize()throws Exception{
        connection=DriverManager.getConnection(System.getenv("NEXION_TEST_DB_URL"),System.getenv("NEXION_TEST_DB_USERNAME"),System.getenv("NEXION_TEST_DB_PASSWORD"));
        database="compute_v2_admission_"+UUID.randomUUID().toString().replace("-","");
        var source=new SingleConnectionDataSource(connection,true);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");jdbc.execute("USE "+database);
        jdbc.execute("CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,pending_deactivate TINYINT,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_task(task_no VARCHAR(96),user_id BIGINT,user_device_id BIGINT,is_deleted TINYINT,source_environment VARCHAR(32),status VARCHAR(32),task_config_id VARCHAR(64),completed_at DATETIME)");
        LegacyZeroCreditResourceSchema.requests(jdbc);
        LegacyZeroCreditResourceSchema.resolutions(jdbc);
        jdbc.execute("CREATE TABLE nx_user_wallet(id BIGINT PRIMARY KEY,user_id BIGINT,fund_incarnation CHAR(36),is_deleted TINYINT,sandbox TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_v2_event(task_no VARCHAR(96),generation BIGINT,deployment_scope VARCHAR(32),kind VARCHAR(16),payload JSON)");
        var configuration=new Configuration(new Environment("compute-v2-admission",new SpringManagedTransactionFactory(),source));
        configuration.addMapper(ComputeV2AdmissionMapper.class);
        mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(ComputeV2AdmissionMapper.class);
    }
    @BeforeEach void rows(){
        for(String table:new String[]{"nx_user_device","nx_compute_task","nx_test_cloud_request","nx_user_wallet","nx_compute_v2_event","nx_test_cloud_zero_credit_resolution"})jdbc.execute("TRUNCATE TABLE "+table);
        jdbc.update("INSERT INTO nx_user_device VALUES(11,7,0,0),(12,8,1,0)");
        jdbc.update("INSERT INTO nx_user_wallet VALUES(70,7,'11111111-1111-1111-1111-111111111111',0,0),(80,8,'22222222-2222-2222-2222-222222222222',0,1)");
    }
    @AfterAll void cleanup()throws Exception{
        if(database!=null){assertThat(database).matches("compute_v2_admission_[a-f0-9]{32}");jdbc.execute("DROP DATABASE "+database);}if(connection!=null)connection.close();
    }
    @Test void accountDeviceAndWalletScopeAreEnforcedByTheActualSql(){
        assertThat(mapper.pendingDeactivate(11,7)).isZero();assertThat(mapper.pendingDeactivate(11,8)).isNull();
        assertThat(mapper.lockWallets(7)).singleElement().satisfies(row->{assertThat(((Number)row.get("id")).longValue()).isEqualTo(70);assertThat(row.get("incarnation")).isEqualTo("11111111-1111-1111-1111-111111111111");});
        assertThat(mapper.lockWallets(8)).isEmpty();
    }
    @Test void legacyUnknownAndActiveWorkBlockAdmissionWhileV2SnapshotsDoNot(){
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,is_deleted,source_environment,status) VALUES('CV2-current',7,11,0,'PRODUCTION','RUNNING'),('CTA-legacy',7,11,0,'PRODUCTION','RUNNING')");
        assertThat(mapper.unresolvedLegacy(7,11)).isEqualTo(1);
        jdbc.update("UPDATE nx_compute_task SET status='COMPLETED' WHERE task_no='CTA-legacy'");
        assertThat(mapper.unresolvedLegacy(7,11)).isZero();
        jdbc.update("INSERT INTO nx_test_cloud_request(owner_id,device_id,state) VALUES(7,11,'UNKNOWN'),(8,12,'PROCESSING')");
        assertThat(mapper.unresolvedLegacy(7,11)).isEqualTo(1);
        jdbc.update("UPDATE nx_test_cloud_request SET state='COMMITTED' WHERE owner_id=7");assertThat(mapper.unresolvedLegacy(7,11)).isZero();
    }
    @Test void resultAndExitFallbackUseExactEnvironmentTaskAndGeneration()throws Exception{
        jdbc.update("INSERT INTO nx_compute_v2_event VALUES('CV2-A',1,'TEST','STARTED','{\"marker\":\"start\"}'),('CV2-A',1,'TEST','VERIFIED','{\"marker\":\"verified\"}'),('CV2-A',1,'TEST','EXITED','{\"marker\":\"exit\"}'),('CV2-B',1,'OTHER','VERIFIED','{\"marker\":\"foreign\"}')");
        assertThat(new ObjectMapper().readTree(mapper.retainedPayload("CV2-A")).path("marker").asText()).isEqualTo("verified");
        assertThat(mapper.retainedPayload("CV2-B")).isNull();assertThat(mapper.exitCount("CV2-A",1)).isEqualTo(1);
        assertThat(mapper.exitCount("CV2-A",2)).isZero();assertThat(mapper.exitCount("CV2-B",1)).isZero();
    }
}
