package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.bi.mapper.L6BehaviorEvidenceMapper;
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
@EnabledIfEnvironmentVariable(named="NEXION_L6_EVIDENCE_MYSQL",matches="true")
class L6BehaviorEvidenceMySqlTest {
    static final String SCHEMA="bug4_l6_"+UUID.randomUUID().toString().replace("-","");
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static L6BehaviorEvidenceMapper mapper;
    static DriverManagerDataSource source(String schema) {
        if(!schema.isEmpty()&&!schema.equals(SCHEMA)) throw new IllegalArgumentException("UNOWNED_SCHEMA");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:18362/"+schema
                +"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "root",System.getenv("NEXION_L6_EVIDENCE_MYSQL_PASSWORD"));
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
        jdbc.execute("CREATE TABLE nx_audit_log(biz_no VARCHAR(96),detail_json JSON,is_deleted INT DEFAULT 0,KEY idx_audit_biz_no(biz_no))");
        jdbc.execute("CREATE TABLE nx_event_schema_registry(event_name VARCHAR(128) PRIMARY KEY,status VARCHAR(16),is_deleted INT)");
        jdbc.update("INSERT INTO nx_event_schema_registry VALUES('app.page_viewed','ACTIVE',0),('app.element_clicked','ACTIVE',0)");
        var config=new Configuration(new Environment("isolated-l6",new SpringManagedTransactionFactory(),source));
        config.addMapper(L6BehaviorEvidenceMapper.class);config.addMapper(A4OutboxDiagnosticsMapper.class);
        sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=sessions.getMapper(L6BehaviorEvidenceMapper.class);
    }
    @AfterAll static void cleanup() {
        if(SCHEMA.matches("bug4_l6_[a-f0-9]{32}")) new JdbcTemplate(source("")).execute("DROP DATABASE IF EXISTS "+SCHEMA);
    }
    @BeforeEach void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_receipt");jdbc.execute("DROP TRIGGER IF EXISTS reject_publication");
        for(String table:List.of("nx_event_consumer_delivery","nx_event_outbox","nx_behavior_event_fact")) jdbc.update("DELETE FROM "+table);
    }
    static L6BehaviorEvidenceService service(L6BehaviorEvidenceMapper evidence) {
        var target=new L6BehaviorEvidenceService(evidence,mock(EventOutboxService.class),new ObjectMapper());
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (L6BehaviorEvidenceService)proxy.getProxy();
    }
    long event(String type,boolean fact) {
        String eventId=UUID.randomUUID().toString().replace("-","");var m=L6BehaviorEvidenceServiceTest.message(type);
        jdbc.update("""
            INSERT INTO nx_event_outbox(event_id,event_type,event_name,aggregate_type,aggregate_id,
             is_server_authoritative,schema_registered,analytics_event,schema_revision,payload)
            VALUES(?,?,?,'APP_BEHAVIOR',?,false,true,true,1,?)
            """,eventId,type,type,m.getAggregateId(),m.getPayload().replace(L6BehaviorEvidenceServiceTest.ID,eventId));
        if(fact) jdbc.update("""
            INSERT INTO nx_behavior_event_fact(event_id,event_name,session_hash,actor_hash,route,page_level,parent_l1,
             dwell_ms,x_norm,y_norm,zone,device_type,locale,source_environment,occurred_at)
            VALUES(?,?,?,?,'/pages/me/me',1,'/pages/me/me',250,0.1000,0.5000,'CONTENT','H5','zh','PRODUCTION','2020-01-01')
            """,eventId,type,L6BehaviorEvidenceServiceTest.SESSION,L6BehaviorEvidenceServiceTest.ACTOR);
        return jdbc.queryForObject("SELECT id FROM nx_event_outbox WHERE event_id=?",Long.class,eventId);
    }
    String status(long id) {return jdbc.queryForObject("SELECT status FROM nx_event_outbox WHERE id=?",String.class,id);}
    int receipts() {return jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_consumer_delivery",Integer.class);}

    @Test void bothFamiliesPublishOnceWithoutInsertingOrChangingFacts() {
        long page=event("app.page_viewed",true),click=event("app.element_clicked",true);
        var before=jdbc.queryForList("SELECT * FROM nx_behavior_event_fact ORDER BY id");var consumer=service(mapper);
        assertThat(consumer.consume(page)).isTrue();assertThat(consumer.consume(click)).isTrue();
        assertThat(consumer.consume(page)).isFalse();assertThat(receipts()).isEqualTo(2);
        assertThat(status(page)).isEqualTo("PUBLISHED");assertThat(status(click)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForList("SELECT * FROM nx_behavior_event_fact ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT status,attempt_count,created_commissions FROM nx_event_consumer_delivery"))
                .allSatisfy(r->{assertThat(r.get("status")).isEqualTo("SUCCESS");assertThat(r.get("attempt_count")).isEqualTo(1);assertThat(r.get("created_commissions")).isEqualTo(1);});
    }
    @Test void missingHistoricalFactsCannotStarveValidNewRowsAndRemainDiagnosable() {
        for(int i=0;i<150;i++) event("app.page_viewed",false);
        long valid=event("app.page_viewed",true),other=event("wallet.ledger_posted",true);
        assertThat(mapper.eligible(100)).containsExactly(valid);
        assertThat(service(mapper).consume(valid)).isTrue();assertThat(status(other)).isEqualTo("PENDING");
        var diagnostics=sessions.getMapper(A4OutboxDiagnosticsMapper.class).page(0,"app.page_viewed",null,false,200);
        assertThat(diagnostics).hasSize(150).allSatisfy(r-> {assertThat(r.errorCode()).isEqualTo("L6_EVIDENCE_FACT_MISSING");assertThat(r.retryCount()).isZero();});
    }
    @Test void databaseCollationAliasesAndSandboxCannotBeAcknowledged() {
        for(String alias:List.of("APP.PAGE_VIEWED","app.páge_viewed","app.page_viewed ")) event(alias,true);
        long canonical=event("app.page_viewed",true);
        assertThat(mapper.eligible(100)).containsExactly(canonical);
        jdbc.update("UPDATE nx_behavior_event_fact SET source_environment='SANDBOX' WHERE event_id=(SELECT event_id FROM nx_event_outbox WHERE id=?)",canonical);
        assertThatThrownBy(()->service(mapper).consume(canonical)).hasMessage("L6_EVIDENCE_FACT_CONFLICT");
        assertThat(receipts()).isZero();assertThat(status(canonical)).isEqualTo("PENDING");
        jdbc.update("UPDATE nx_behavior_event_fact SET event_id=UPPER(event_id) WHERE event_id=(SELECT event_id FROM nx_event_outbox WHERE id=?)",canonical);
        assertThat(mapper.eligible(100)).isEmpty();
    }
    @Test void factRemovedBetweenScanAndLockStaysPendingWithoutRetryOrReceipt() {
        long id=event("app.page_viewed",true);assertThat(mapper.eligible(100)).contains(id);
        jdbc.update("DELETE FROM nx_behavior_event_fact");assertThat(service(mapper).consume(id)).isFalse();
        assertThat(status(id)).isEqualTo("PENDING");assertThat(receipts()).isZero();
        assertThat(jdbc.queryForObject("SELECT retry_count FROM nx_event_outbox WHERE id=?",Integer.class,id)).isZero();
    }
    @Test void receiptDatabaseFailureRollsBackAndNeverPublishes() {
        long id=event("app.page_viewed",true);
        jdbc.execute("CREATE TRIGGER reject_receipt BEFORE INSERT ON nx_event_consumer_delivery FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture receipt rejected'");
        assertThatThrownBy(()->service(mapper).consume(id)).isInstanceOf(RuntimeException.class);
        assertThat(status(id)).isEqualTo("PENDING");assertThat(receipts()).isZero();
    }
    @Test void publicationDatabaseFailureRollsBackTheAlreadyInsertedReceipt() {
        long id=event("app.page_viewed",true);
        jdbc.execute("CREATE TRIGGER reject_publication BEFORE UPDATE ON nx_event_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture publication rejected'");
        assertThatThrownBy(()->service(mapper).consume(id)).isInstanceOf(RuntimeException.class);
        assertThat(status(id)).isEqualTo("PENDING");assertThat(receipts()).isZero();
    }
    @Test void concurrentDispatchersProduceExactlyOneReceipt() throws Exception {
        long id=event("app.page_viewed",true);var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{start.await();return service(mapper).consume(id);});
            var b=executor.submit(()->{start.await();return service(mapper).consume(id);});start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(receipts()).isEqualTo(1);assertThat(status(id)).isEqualTo("PUBLISHED");
        } finally {executor.shutdownNow();}
    }
    @Test void retentionCannotDeleteProofBetweenVerificationAndReceiptCommit() throws Exception {
        long id=event("app.page_viewed",true);var locked=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var instrumented=mock(L6BehaviorEvidenceMapper.class,org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doAnswer(call->{var f=mapper.fact(call.getArgument(0));locked.countDown();assertThat(finish.await(5,TimeUnit.SECONDS)).isTrue();return f;})
                .when(instrumented).fact(anyString());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var consumer=executor.submit(()->service(instrumented).consume(id));assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var cleanupStarted=new CountDownLatch(1);
            var cleanup=executor.submit(()->{cleanupStarted.countDown();return jdbc.update("DELETE FROM nx_behavior_event_fact WHERE occurred_at<'2021-01-01' ORDER BY occurred_at,id LIMIT 200");});
            assertThat(cleanupStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->cleanup.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            finish.countDown();assertThat(consumer.get(5,TimeUnit.SECONDS)).isTrue();assertThat(cleanup.get(5,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(receipts()).isEqualTo(1);assertThat(status(id)).isEqualTo("PUBLISHED");
        } finally {finish.countDown();executor.shutdownNow();}
    }
    @Test void rejectedProofStaysPendingAndDoesNotBlockTheFollowingValidRow() {
        long bad=event("app.page_viewed",true),good=event("app.element_clicked",true);
        jdbc.update("UPDATE nx_behavior_event_fact SET actor_hash=? WHERE event_id=(SELECT event_id FROM nx_event_outbox WHERE id=?)","d".repeat(64),bad);
        new L6BehaviorEvidenceScheduler(mapper,service(mapper)).dispatchPending();
        assertThat(status(bad)).isEqualTo("PENDING");assertThat(status(good)).isEqualTo("PUBLISHED");assertThat(receipts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM nx_event_outbox WHERE id=?",Integer.class,bad)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM nx_event_outbox WHERE id=?",String.class,bad)).isEqualTo("L6_EVIDENCE_FACT_CONFLICT");
        assertThat(mapper.eligible(100)).isEmpty();
    }
    @Test void eligibleScanUsesExistingIndexesAcrossLargePublishedPrefix() {
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        jdbc.update("INSERT INTO nx_event_outbox(event_id,event_type,status) SELECT LPAD(CONCAT('f',a.n,b.n,c.n,d.n),32,'0'),'app.page_viewed','PUBLISHED' FROM "
                +digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d");
        long id=event("app.page_viewed",true);
        var statement=sessions.getConfiguration().getMappedStatement(L6BehaviorEvidenceMapper.class.getName()+".eligible");
        var plan=jdbc.queryForList("EXPLAIN "+statement.getBoundSql(Map.of("limit",100)).getSql(),100);
        System.out.println("L6 eligible scan 10000 terminal prefix EXPLAIN: "+plan);
        assertThat(plan).anySatisfy(row->{assertThat(row.get("table")).isEqualTo("o");assertThat(row.get("possible_keys")).isNotNull();});
        assertThat(mapper.eligible(100)).containsExactly(id);
    }
}
