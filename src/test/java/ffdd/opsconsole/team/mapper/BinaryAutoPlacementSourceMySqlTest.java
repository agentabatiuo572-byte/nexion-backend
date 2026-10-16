package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual three production read queries against an owned empty MySQL schema. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BinaryAutoPlacementSourceMySqlTest {
    Connection admin; String schema; JdbcTemplate jdbc;
    BinaryCommissionSettlementMapper binary; TeamCommissionMapper team;
    @BeforeAll void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_f3_source_"+UUID.randomUUID().toString().replace("-","");
        try(var s=admin.createStatement()){s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");}
        System.out.println("F3_SOURCE_FIXTURE_CREATE="+schema);
        int q=url.indexOf('?');
        String fixture=url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q));
        var ds=new DriverManagerDataSource(fixture,user,password){
            @Override public Connection getConnection() throws SQLException {
                Connection c=super.getConnection();
                // Only this owned fixture uses a fixed session clock, making the seven-day edges exact.
                try(var s=c.createStatement()){s.execute("SET timestamp=1792137600");}
                return c;
            }
        };
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(20),is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_team_member(user_id BIGINT,member_user_id BIGINT,level INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_binary_leg_assignment(id BIGINT AUTO_INCREMENT PRIMARY KEY,owner_user_id BIGINT,member_user_id BIGINT,leg VARCHAR(1),assigned_by_admin_id BIGINT,assigned_by VARCHAR(80),assigned_at DATETIME,UNIQUE(owner_user_id,member_user_id))");
        jdbc.execute("CREATE TABLE nx_audit_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,action VARCHAR(80),resource_type VARCHAR(80),resource_id VARCHAR(80),biz_no VARCHAR(80),user_id BIGINT,actor_id BIGINT,actor_type VARCHAR(20),actor_username VARCHAR(80),result VARCHAR(20),created_at DATETIME,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_binary_commission_settlement(user_id BIGINT,status VARCHAR(20),settlement_date DATE,matched_volume DECIMAL(24,6),daily_cap_usdt DECIMAL(24,6),left_volume DECIMAL(24,6),right_volume DECIMAL(24,6),is_deleted INT)");
        var config=new Configuration(new Environment("f3-source",new SpringManagedTransactionFactory(),ds));
        config.addMapper(BinaryCommissionSettlementMapper.class);config.addMapper(TeamCommissionMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        binary=session.getMapper(BinaryCommissionSettlementMapper.class);team=session.getMapper(TeamCommissionMapper.class);
    }
    @BeforeEach void reset(){
        for(String t:List.of("nx_audit_log","nx_binary_leg_assignment","nx_team_member","nx_user","nx_binary_commission_settlement"))jdbc.execute("DELETE FROM "+t);
        jdbc.update("INSERT INTO nx_user VALUES(7,'ACTIVE',0),(8,'ACTIVE',0),(11,'ACTIVE',0),(12,'ACTIVE',0),(13,'ACTIVE',0),(14,'ACTIVE',0),(15,'ACTIVE',0)");
        jdbc.update("INSERT INTO nx_team_member VALUES(7,11,1,0),(7,12,1,0),(7,13,1,0),(7,14,1,0),(7,15,1,0),(8,11,1,0)");
    }
    @AfterAll void cleanup() throws Exception {
        try(var s=admin.createStatement()){s.execute("DROP DATABASE `"+schema+"`");System.out.println("F3_SOURCE_FIXTURE_DROP="+schema);}finally{admin.close();}
    }
    void assignment(long owner,long member,String actor){jdbc.update("INSERT INTO nx_binary_leg_assignment(owner_user_id,member_user_id,leg,assigned_by_admin_id,assigned_by,assigned_at) VALUES(?,?,'A',99,?,NOW())",owner,member,actor);}
    void audit(long owner,long member){jdbc.update("INSERT INTO nx_audit_log(action,resource_type,resource_id,biz_no,user_id,actor_id,actor_type,actor_username,result,created_at,is_deleted) VALUES('F3_BINARY_AUTO_PLACED','BINARY_LEG_ASSIGNMENT',?,?,?,99,'ADMIN','superadmin','SUCCESS',NOW(),0)",owner+":"+member,owner+":"+member,owner);}
    int sevenDays(){return ((Number)team.binarySettlementSummary().get("autoPlacement7dCount")).intValue();}
    String source(long member){return binary.listAssignmentMembers(7L,member,"ALL",0L,10).get(0).assignmentSource();}
    @Test void systemAndAdministratorAutomationAgreeAcrossAllThreeReadModels(){
        assignment(7,11,"SYSTEM_AUTO_PLACEMENT");assignment(7,12,"superadmin");audit(7,12);audit(7,12);
        assignment(7,13,"superadmin");assignment(8,11,"superadmin");audit(8,11);
        assertThat(sevenDays()).isEqualTo(3);
        assertThat(binary.countAutoPlacedMembers(7L)).isEqualTo(2);
        assertThat(binary.countAutoPlacedMembers(8L)).isEqualTo(1);
        assertThat(source(11)).isEqualTo("AUTO");assertThat(source(12)).isEqualTo("AUTO");
        assertThat(source(13)).isEqualTo("MANUAL");assertThat(source(14)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_binary_leg_assignment",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Integer.class)).isEqualTo(3);
    }
    @Test void failedWrongObjectOrUnrelatedAuditCannotReclassifyManualPlacement(){
        for(String mutation:List.of("UPDATE nx_audit_log SET result='FAILED'","UPDATE nx_audit_log SET action='F3_BINARY_MANUAL_PLACED'",
          "UPDATE nx_audit_log SET resource_type='OTHER'","UPDATE nx_audit_log SET resource_id='7:13'","UPDATE nx_audit_log SET biz_no='8:12'",
          "UPDATE nx_audit_log SET user_id=8","UPDATE nx_audit_log SET actor_id=100","UPDATE nx_audit_log SET actor_username='other'",
          "UPDATE nx_audit_log SET actor_type='SYSTEM'","UPDATE nx_audit_log SET is_deleted=1","UPDATE nx_audit_log SET created_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)")){
            reset();assignment(7,12,"superadmin");audit(7,12);jdbc.execute(mutation);
            assertThat(sevenDays()).as(mutation).isZero();
            assertThat(binary.countAutoPlacedMembers(7L)).as(mutation).isZero();
            assertThat(source(12)).as(mutation).isEqualTo("MANUAL");
        }
    }
    @Test void oldMarkerStaysAutomaticAndMissingHistoricalAuditDoesNotInventAutomation(){
        assignment(7,11,"SYSTEM_AUTO_PLACEMENT");assignment(7,12,"superadmin");
        jdbc.update("UPDATE nx_binary_leg_assignment SET assigned_at=DATE_SUB(NOW(),INTERVAL 30 DAY)");
        assertThat(sevenDays()).isZero();assertThat(binary.countAutoPlacedMembers(7L)).isEqualTo(1);
        assertThat(source(11)).isEqualTo("AUTO");assertThat(source(12)).isEqualTo("MANUAL");
    }
    @Test void sevenDayWindowUsesAssignmentTimeInclusiveAndExcludesFutureRows(){
        for(long member:List.of(11L,12L,13L,14L,15L)){assignment(7,member,"superadmin");audit(7,member);}
        jdbc.update("UPDATE nx_binary_leg_assignment SET assigned_at=DATE_SUB(NOW(),INTERVAL 7 DAY) WHERE member_user_id=11");
        jdbc.update("UPDATE nx_binary_leg_assignment SET assigned_at=DATE_SUB(DATE_SUB(NOW(),INTERVAL 7 DAY),INTERVAL 1 SECOND) WHERE member_user_id=12");
        jdbc.update("UPDATE nx_binary_leg_assignment SET assigned_at=DATE_ADD(NOW(),INTERVAL 1 SECOND) WHERE member_user_id=13");
        jdbc.update("UPDATE nx_audit_log SET created_at=DATE_ADD(NOW(),INTERVAL 1 SECOND) WHERE resource_id='7:13'");
        jdbc.update("UPDATE nx_binary_leg_assignment SET assigned_at=NULL WHERE member_user_id=15");
        assertThat(sevenDays()).isEqualTo(2);
        assertThat(source(11)).isEqualTo("AUTO");
    }
}
