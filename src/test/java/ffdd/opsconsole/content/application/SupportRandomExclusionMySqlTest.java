package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real mapper statements and forward migration; only synthetic rows in a newly owned schema. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
class SupportRandomExclusionMySqlTest {
    Connection admin;
    String schema;
    SupportBindingMapper mapper;
    @BeforeEach void create() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        assertThat(url).matches("jdbc:mysql://[^/]+/([?].*)?");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"), password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_random_"+UUID.randomUUID().toString().replace("-","");
        try(var s=admin.createStatement()){s.execute("CREATE DATABASE `"+schema+"`");}
        admin.setCatalog(schema);
        try(var s=admin.createStatement()) {
            s.execute("CREATE TABLE nx_support_binding_pool(customer_id BIGINT PRIMARY KEY,reason VARCHAR(32),version BIGINT DEFAULT 1,entered_at DATETIME(6),auto_eligible BOOLEAN DEFAULT FALSE,auto_rule_version BIGINT,auto_attempt_state VARCHAR(24) DEFAULT 'NONE',attempts INT DEFAULT 0,last_attempt_at DATETIME(6),last_outcome VARCHAR(64),operation_id VARCHAR(128))");
            s.execute("INSERT INTO nx_support_binding_pool(customer_id,reason,version,entered_at) VALUES(1,'DEPTH_LIMIT',7,'2026-10-01 01:00:00')");
        }
        migrate();
        int q=url.indexOf('?');String fixtureUrl=url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q));
        var source=new DriverManagerDataSource(fixtureUrl,user,password);
        var config=new Configuration(new Environment("support-exclusion",new SpringManagedTransactionFactory(),source));
        config.addMapper(SupportBindingMapper.class);
        mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(SupportBindingMapper.class);
        System.out.println("EXCLUSION_FIXTURE_CREATE="+schema);
    }
    void migrate() throws Exception {
        String sql=Files.readString(Path.of("scripts/migrations/20261016_support_depth_limit_exclusion.sql")).replaceAll("(?m)^--.*$","");
        try(var statement=admin.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())statement.execute(part);}
    }
    @AfterEach void drop() throws Exception {
        if(admin==null)return;
        try {if(schema!=null)try(var s=admin.createStatement()){s.execute("DROP DATABASE `"+schema+"`");System.out.println("EXCLUSION_FIXTURE_DROP="+schema);}}
        finally{admin.close();}
    }
    @Test void migrationIsRepeatableAndDoesNotInventLegacyIdentityOrAutomaticEligibility() throws Exception {
        migrate();var legacy=mapper.poolAttempt(1L);
        assertThat(legacy.get("reason")).isEqualTo("DEPTH_LIMIT");assertThat(legacy.get("excludedAgentAdminId")).isNull();
        assertThat(mapper.autoEligible(1L)).isFalse();assertThat(mapper.poolVersion(1L)).isEqualTo(7L);
        try(var s=admin.createStatement();var r=s.executeQuery("SELECT entered_at FROM nx_support_binding_pool WHERE customer_id=1")){
            assertThat(r.next()).isTrue();assertThat(r.getObject(1,java.time.LocalDateTime.class)).isEqualTo(java.time.LocalDateTime.of(2026,10,1,1,0));
        }
    }
    @Test void actualInsertAndAttemptUpdatesRetainTheOriginalIdentity() throws Exception {
        assertThat(mapper.enterDepthLimitedPool(30L,11L)).isEqualTo(1);
        mapper.eligiblePool(30L,2L,"registration:30");mapper.attemptPool(30L,"WAITING_CANDIDATE","NO_CANDIDATE");
        assertThat(((Number)mapper.poolAttempt(30L).get("excludedAgentAdminId")).longValue()).isEqualTo(11L);
        migrate();mapper.attemptPool(30L,"ASSIGNED","ASSIGNED");
        assertThat(((Number)mapper.poolAttempt(30L).get("excludedAgentAdminId")).longValue()).isEqualTo(11L);
        assertThat(mapper.autoEligible(30L)).isTrue();
        mapper.enterPool(31L,"NO_INVITER");assertThat(mapper.poolAttempt(31L).get("excludedAgentAdminId")).isNull();assertThat(mapper.autoEligible(31L)).isFalse();
        assertThatThrownBy(()->mapper.enterDepthLimitedPool(30L,12L)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(((Number)mapper.poolAttempt(30L).get("excludedAgentAdminId")).longValue()).isEqualTo(11L);
    }
}
