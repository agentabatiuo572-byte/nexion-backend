package ffdd.opsconsole.onboarding.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes production mapper SQL against the canonical runtime DDL in an owned MySQL schema. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhoneLoginRuntimeMySqlTest {
    Connection admin; String schema; boolean created; JdbcTemplate jdbc;
    OnboardingCalibrationMapper mapper; AppTaskAssignmentMapper tasks; LocalDateTime observed;
    @BeforeAll void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if(url==null || !url.matches("jdbc:mysql://[^/]+/([?].*)?")) throw new IllegalArgumentException("Server URL without catalog required");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_phone_login_test_"+UUID.randomUUID().toString().replace("-","");
        try(var s=admin.createStatement()){s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;}
        System.out.println("PHONE_LOGIN_FIXTURE_CREATE="+schema);
        int q=url.indexOf('?');
        var ds=new DriverManagerDataSource(url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q)),user,password);
        jdbc=new JdbcTemplate(ds);
        String source=Files.readString(Path.of("scripts/schema.sql"));
        int start=source.indexOf("CREATE TABLE IF NOT EXISTS nx_user_device_runtime (");
        jdbc.execute(source.substring(start,source.indexOf(';',start)+1));
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,source_channel VARCHAR(32),source_environment VARCHAR(32),run_id VARCHAR(96),device_type VARCHAR(32),is_deleted INT)");
        var config=new Configuration(new Environment("phone-login",new SpringManagedTransactionFactory(),ds));
        config.addMapper(OnboardingCalibrationMapper.class);config.addMapper(AppTaskAssignmentMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper=session.getMapper(OnboardingCalibrationMapper.class);tasks=session.getMapper(AppTaskAssignmentMapper.class);
    }
    @BeforeEach void reset(){
        jdbc.execute("DELETE FROM nx_user_device_runtime");jdbc.execute("DELETE FROM nx_user_device");jdbc.execute("DELETE FROM nx_user");
        observed=LocalDateTime.now().withNano(0);
        jdbc.update("INSERT INTO nx_user VALUES(1,'ACTIVE',0,0)");
        jdbc.update("INSERT INTO nx_user_device VALUES(11,1,'ONBOARDING','PRODUCTION','','PHONE',0),(12,1,'ONBOARDING','PRODUCTION','','PHONE',0),(13,2,'ONBOARDING','PRODUCTION','','PHONE',0),(14,1,'STORE','PRODUCTION','','PHONE',0),(15,1,'ONBOARDING','SANDBOX','','PHONE',0),(16,1,'ONBOARDING','PRODUCTION','other-run','PHONE',0),(17,1,'ONBOARDING','PRODUCTION','','BOX',0),(18,1,'ONBOARDING','PRODUCTION','','PHONE',1),(19,1,'ONBOARDING','PRODUCTION','','PHONE',0),(20,1,'ONBOARDING','PRODUCTION','','PHONE',0)");
        jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,online_status,battery_level,network_reachable,active_task_no,heartbeat_at,is_deleted) SELECT id,'ONLINE',80,1,CONCAT('TASK-',id),?,IF(id=20,1,0) FROM nx_user_device",observed);
        jdbc.update("UPDATE nx_user_device_runtime SET paused_reason='ADMIN_PAUSED' WHERE user_device_id=19");
    }
    @AfterAll void cleanup() throws Exception {
        if(admin==null)return;
        try{if(created)try(var s=admin.createStatement()){s.execute("DROP DATABASE `"+schema+"`");System.out.println("PHONE_LOGIN_FIXTURE_DROP="+schema);}}finally{admin.close();}
    }
    void assertOfflineWithoutInventingHeartbeat(long id){
        var row=jdbc.queryForMap("SELECT online_status,active_task_no,heartbeat_at,network_reachable FROM nx_user_device_runtime WHERE user_device_id=?",id);
        assertThat(row.get("online_status")).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT heartbeat_at FROM nx_user_device_runtime WHERE user_device_id=?",LocalDateTime.class,id)).isEqualTo(observed);
        assertThat(jdbc.queryForObject("SELECT network_reachable FROM nx_user_device_runtime WHERE user_device_id=?",Integer.class,id)).isZero();
    }
    @Test void replacementStopsOnlyOriginalPhonesWithNotNullHeartbeatSchema(){
        assertThat(mapper.clearReplacedPhoneRuntime(1L,12L)).isEqualTo(2);
        assertOfflineWithoutInventingHeartbeat(11);assertOfflineWithoutInventingHeartbeat(19);
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime WHERE user_device_id=11",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime WHERE user_device_id=11",String.class)).isEqualTo("PHONE_REPLACED");
        assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime WHERE user_device_id=19",String.class)).isEqualTo("ADMIN_PAUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime WHERE user_device_id NOT IN(11,19) AND online_status='ONLINE' AND network_reachable=1 AND active_task_no IS NOT NULL",Integer.class)).isEqualTo(8);
    }
    @Test void returningInstallationClearsOnlyReplacementPauseAndRequiresNewTelemetry(){
        jdbc.update("UPDATE nx_user_device_runtime SET paused_reason='PHONE_REPLACED',active_task_no=NULL WHERE user_device_id=11");
        assertThat(mapper.restoreBoundPhoneRuntime(1L,11L)).isEqualTo(1);
        assertOfflineWithoutInventingHeartbeat(11);
        assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime WHERE user_device_id=11",String.class)).isNull();
        assertThat(mapper.restoreBoundPhoneRuntime(1L,19L)).isZero();
        assertThat(mapper.restoreBoundPhoneRuntime(2L,11L)).isZero();
        assertThat(tasks.phoneRuntime(1L,11L).networkReachable()).isFalse();
        assertThat(tasks.phoneRuntime(1L,11L).heartbeatAt()).isEqualTo(observed);
        LocalDateTime fresh=observed.plusSeconds(1);
        assertThat(tasks.upsertPhoneRuntime(1L,11L,80,false,true,"ONLINE",null,fresh)).isPositive();
        assertThat(tasks.phoneRuntime(1L,11L).networkReachable()).isTrue();
        assertThat(tasks.phoneRuntime(1L,11L).heartbeatAt()).isEqualTo(fresh);
    }
}
