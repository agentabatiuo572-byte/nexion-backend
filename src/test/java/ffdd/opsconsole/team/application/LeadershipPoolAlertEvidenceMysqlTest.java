package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.team.mapper.LeadershipPoolAlertEvidenceMapper;
import ffdd.opsconsole.platform.mapper.A4OutboxDiagnosticsMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Runs only in a disposable, task-owned schema on the verified loopback MySQL instance. */
@EnabledIfEnvironmentVariable(named="NEXION_F4_ALERT_MYSQL",matches="true")
class LeadershipPoolAlertEvidenceMySqlTest {
    static final String SCHEMA="bug4_f4_"+UUID.randomUUID().toString().replace("-","");
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static LeadershipPoolAlertEvidenceMapper mapper;
    static DriverManagerDataSource source(String schema) {
        if(!schema.isEmpty()&&!schema.equals(SCHEMA)) throw new IllegalArgumentException("UNOWNED_SCHEMA");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:18362/"+schema
                +"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "root",System.getenv("NEXION_F4_ALERT_MYSQL_PASSWORD"));
    }
    @BeforeAll static void setupSchema() {
        new JdbcTemplate(source("")).execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        source=source(SCHEMA);jdbc=new JdbcTemplate(source);
        jdbc.execute("""
            CREATE TABLE nx_event_outbox (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(64) NOT NULL,aggregate_type VARCHAR(64),aggregate_id VARCHAR(128),
              event_type VARCHAR(96),event_name VARCHAR(96),family_key VARCHAR(64),event_ts DATETIME,phase VARCHAR(32),
              account_age_months INT,cohort VARCHAR(32),is_server_authoritative BOOLEAN,schema_revision INT,
              schema_registered BOOLEAN,analytics_event BOOLEAN,payload JSON,status VARCHAR(32) DEFAULT 'PENDING',
              retry_count INT DEFAULT 0,next_retry_at DATETIME,published_at DATETIME,last_error VARCHAR(512),
              created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME,is_deleted INT DEFAULT 0,
              UNIQUE KEY uk_event_outbox_event_id(event_id),KEY idx_event_outbox_status_next(status,next_retry_at,id),
              KEY idx_event_outbox_type_time(event_type,created_at))
            """);
        jdbc.execute("""
            CREATE TABLE nx_behavior_event_fact (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(64) NOT NULL,event_name VARCHAR(64),
              session_hash CHAR(64),actor_hash CHAR(64),route VARCHAR(160),page_level TINYINT,parent_l1 VARCHAR(160),
              parent_l2 VARCHAR(160),dwell_ms BIGINT,x_norm DECIMAL(6,4),y_norm DECIMAL(6,4),zone VARCHAR(16),
              element_id VARCHAR(64),device_type VARCHAR(16),locale VARCHAR(16),source_environment VARCHAR(16),
              occurred_at DATETIME(3),UNIQUE KEY uk_behavior_event_id(event_id))
            """);
        jdbc.execute("""
            CREATE TABLE nx_event_consumer_delivery (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(64),consumer_group VARCHAR(128),topic VARCHAR(128),
              msg_id VARCHAR(128),event_type VARCHAR(96),aggregate_type VARCHAR(64),aggregate_id VARCHAR(128),
              status VARCHAR(32),attempt_count INT,rocketmq_reconsume_times INT,next_retry_at DATETIME,
              processed_at DATETIME,dead_at DATETIME,created_commissions INT,last_error VARCHAR(512),
              first_seen_at DATETIME,last_seen_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted INT DEFAULT 0,
              UNIQUE KEY uk_event_consumer_event_group(event_id,consumer_group))
            """);
        jdbc.execute("CREATE TABLE nx_audit_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,biz_no VARCHAR(96),action VARCHAR(96),resource_type VARCHAR(96),resource_id VARCHAR(96),actor_type VARCHAR(32),actor_username VARCHAR(96),result VARCHAR(32),risk_level VARCHAR(32),detail_json JSON,is_deleted INT DEFAULT 0,KEY idx_audit_biz_no(biz_no))");
        jdbc.execute("CREATE TABLE nx_event_schema_registry(event_name VARCHAR(128) PRIMARY KEY,status VARCHAR(16),is_deleted INT)");
        jdbc.update("INSERT INTO nx_event_schema_registry VALUES('app.page_viewed','ACTIVE',0),('app.element_clicked','ACTIVE',0),('leadership_pool.settlement_blocked','ACTIVE',0)");
        var config=new Configuration(new Environment("isolated-f4-alert",new SpringManagedTransactionFactory(),source));
        config.addMapper(LeadershipPoolAlertEvidenceMapper.class);config.addMapper(A4OutboxDiagnosticsMapper.class);
        sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=sessions.getMapper(LeadershipPoolAlertEvidenceMapper.class);
    }
    @AfterAll static void cleanup() {
        if(SCHEMA.matches("bug4_f4_[a-f0-9]{32}")) new JdbcTemplate(source("")).execute("DROP DATABASE IF EXISTS "+SCHEMA);
    }
    @BeforeEach void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_receipt");jdbc.execute("DROP TRIGGER IF EXISTS reject_publication");
        for(String table:List.of("nx_event_consumer_delivery","nx_event_outbox","nx_behavior_event_fact","nx_audit_log")) jdbc.update("DELETE FROM "+table);
    }
    static LeadershipPoolAlertEvidenceService service(LeadershipPoolAlertEvidenceMapper evidence) {
        var target=new LeadershipPoolAlertEvidenceService(evidence,mock(EventOutboxService.class),new ObjectMapper());
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (LeadershipPoolAlertEvidenceService)proxy.getProxy();
    }
    long event(boolean audit) {
        String eventId=UUID.randomUUID().toString().replace("-","");var m=LeadershipPoolAlertEvidenceServiceTest.message();
        String time="2026-09-18T08:00:00."+String.format("%09d",++sequence)+"Z";
        jdbc.update("""
            INSERT INTO nx_event_outbox(event_id,event_type,event_name,aggregate_type,aggregate_id,
             is_server_authoritative,schema_registered,analytics_event,schema_revision,payload)
            VALUES(?,?,?,'LEADERSHIP_POOL_CONFIG','absent',true,true,true,305,?)
            """,eventId,m.getEventType(),m.getEventName(),m.getPayload().replace(LeadershipPoolAlertEvidenceServiceTest.ID,eventId).replace(LeadershipPoolAlertEvidenceServiceTest.TIME,time));
        if(audit) jdbc.update("""
            INSERT INTO nx_audit_log(biz_no,action,resource_type,resource_id,actor_type,actor_username,result,risk_level,detail_json)
            VALUES('F4-CONFIG-BLOCKED-absent','F4_LEADERSHIP_POOL_CONFIG_BLOCKED','LEADERSHIP_POOL_CONFIG',
                   'team.ui.F.pool.configVersion','SYSTEM','SYSTEM','FAILED','HIGH',?)
            """,LeadershipPoolAlertEvidenceServiceTest.fact().detailJson().replace(LeadershipPoolAlertEvidenceServiceTest.TIME,time));
        return jdbc.queryForObject("SELECT id FROM nx_event_outbox WHERE event_id=?",Long.class,eventId);
    }
    static int sequence;
    String status(long id){return jdbc.queryForObject("SELECT status FROM nx_event_outbox WHERE id=?",String.class,id);}
    int receipts(){return jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_consumer_delivery",Integer.class);}
    @Test void existingFailureIsReadOnlyAndReceiptCannotBeMistakenForASettlement(){
        long id=event(true);var before=jdbc.queryForList("SELECT * FROM nx_audit_log");var consumer=service(mapper);
        assertThat(consumer.consume(id)).isTrue();assertThat(consumer.consume(id)).isFalse();assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(receipts()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT created_commissions FROM nx_event_consumer_delivery",Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM nx_audit_log")).isEqualTo(before);
    }
    @Test void missingAuditHeadCannotStarveNewProvenAlert(){
        for(int i=0;i<150;i++)event(false);long id=event(true);
        assertThat(mapper.eligible(100)).containsExactly(id);assertThat(service(mapper).consume(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE status='PENDING'",Integer.class)).isEqualTo(150);
    }
    @Test void auditAliasesAndChangedIdentityRemainUnresolved(){
        long id=event(true);jdbc.update("UPDATE nx_audit_log SET detail_json=JSON_SET(detail_json,'$.source','schéduler')");
        assertThat(mapper.eligible(100)).isEmpty();assertThat(service(mapper).consume(id)).isFalse();
        jdbc.update("UPDATE nx_audit_log SET detail_json=JSON_SET(detail_json,'$.source','scheduler'),actor_username='system'");
        assertThatThrownBy(()->service(mapper).consume(id)).hasMessage("F4_ALERT_AUDIT_CONFLICT");assertThat(receipts()).isZero();
        jdbc.update("UPDATE nx_audit_log SET actor_username='SYSTEM',result='SUCCESS'");
        assertThatThrownBy(()->service(mapper).consume(id)).hasMessage("F4_ALERT_AUDIT_CONFLICT");
    }
    @Test void duplicateAuditsAndAliasedEventTypesNeverPublish(){
        long id=event(true);jdbc.update("INSERT INTO nx_audit_log(biz_no,action,resource_type,resource_id,actor_type,actor_username,result,risk_level,detail_json) SELECT biz_no,action,resource_type,resource_id,actor_type,actor_username,result,risk_level,detail_json FROM nx_audit_log");
        assertThatThrownBy(()->service(mapper).consume(id)).hasMessage("F4_ALERT_AUDIT_NOT_UNIQUE");
        jdbc.update("UPDATE nx_event_outbox SET event_type='léadership_pool.settlement_blocked'");assertThat(mapper.eligible(100)).isEmpty();
        assertThatThrownBy(()->service(mapper).consume(id)).hasMessage("F4_ALERT_ENVELOPE_INVALID");assertThat(status(id)).isEqualTo("PENDING");
    }
    @Test void receiptAndPublicationErrorsRollBackAtomically(){
        long id=event(true);jdbc.execute("CREATE TRIGGER reject_receipt BEFORE INSERT ON nx_event_consumer_delivery FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated receipt rejection'");
        assertThatThrownBy(()->service(mapper).consume(id)).isInstanceOf(RuntimeException.class);assertThat(status(id)).isEqualTo("PENDING");assertThat(receipts()).isZero();
        jdbc.execute("DROP TRIGGER reject_receipt");jdbc.execute("CREATE TRIGGER reject_publication BEFORE UPDATE ON nx_event_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated publication rejection'");
        assertThatThrownBy(()->service(mapper).consume(id)).isInstanceOf(RuntimeException.class);assertThat(status(id)).isEqualTo("PENDING");assertThat(receipts()).isZero();
    }
    @Test void concurrentDifferentEventsCannotClaimOneFailureTwice()throws Exception{
        long id=event(true);String another=UUID.randomUUID().toString().replace("-","");
        jdbc.update("""
            INSERT INTO nx_event_outbox(event_id,event_type,event_name,aggregate_type,aggregate_id,is_server_authoritative,schema_registered,analytics_event,schema_revision,payload)
            SELECT ?,event_type,event_name,aggregate_type,aggregate_id,is_server_authoritative,schema_registered,analytics_event,schema_revision,
              JSON_SET(payload,'$.event_id',?) FROM nx_event_outbox WHERE id=?
            """,another,another,id);
        long other=jdbc.queryForObject("SELECT id FROM nx_event_outbox WHERE event_id=?",Long.class,another);
        var executor=Executors.newFixedThreadPool(2);try{
            var start=new CountDownLatch(1);var consumer=service(mapper);
            var a=executor.submit(()->{start.await();try{return consumer.consume(id);}catch(RuntimeException ex){return false;}});
            var b=executor.submit(()->{start.await();try{return consumer.consume(other);}catch(RuntimeException ex){return false;}});start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(receipts()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE status='PENDING'",Integer.class)).isEqualTo(1);
        }finally{executor.shutdownNow();}
    }
    @Test void auditRetentionWaitsForReceiptTransaction()throws Exception{
        long id=event(true);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var decorated=spy(mapper);
        doAnswer(call->{var result=mapper.facts(id);locked.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");return result;}).when(decorated).facts(id);
        var executor=Executors.newFixedThreadPool(2);try{
            var consuming=executor.submit(()->service(decorated).consume(id));assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var deleting=executor.submit(()->jdbc.update("DELETE FROM nx_audit_log"));Thread.sleep(150);assertThat(deleting.isDone()).isFalse();
            release.countDown();assertThat(consuming.get(5,TimeUnit.SECONDS)).isTrue();assertThat(deleting.get(5,TimeUnit.SECONDS)).isEqualTo(1);assertThat(receipts()).isEqualTo(1);
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void diagnosticsShowsOnlyFixedMissingOrConflictCodes(){
        long missing=event(false),valid=event(true);var diagnostic=sessions.getMapper(A4OutboxDiagnosticsMapper.class);
        assertThat(diagnostic.page(0,LeadershipPoolAlertEvidenceMapper.TYPE,null,false,25).get(0).errorCode()).isEqualTo("F4_ALERT_AUDIT_MISSING");
        mapper.defer(valid,"F4_ALERT_AUDIT_CONFLICT");
        assertThat(diagnostic.page(missing,LeadershipPoolAlertEvidenceMapper.TYPE,null,false,25).get(0).errorCode()).isEqualTo("F4_ALERT_AUDIT_CONFLICT");
        mapper.defer(valid,"private actor secret");
        assertThat(diagnostic.page(missing,LeadershipPoolAlertEvidenceMapper.TYPE,null,false,25).get(0).errorCode()).isEqualTo("OTHER_ERROR");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM nx_event_outbox WHERE id=?",Integer.class,valid)).isZero();
    }

}
