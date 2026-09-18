package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.platform.mapper.A4OutboxDiagnosticsMapper;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Uses only the explicitly authorized isolated loopback server, with a disposable schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_C1_AUDIT_MYSQL", matches = "true")
class A4OutboxDiagnosticsMySqlTest {
    static final String SCHEMA = "bug4_diag_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate jdbc; static SqlSessionFactory factory;
    static DriverManagerDataSource source(String schema) { return new DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:18362/"+schema+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true", "root", System.getenv("NEXION_C1_AUDIT_MYSQL_PASSWORD")); }
    @BeforeAll static void setup() {
        new JdbcTemplate(source("")).execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        jdbc = new JdbcTemplate(source(SCHEMA));
        jdbc.execute("CREATE TABLE nx_event_outbox (id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(64),event_type VARCHAR(96),aggregate_id VARCHAR(128),status VARCHAR(32),retry_count INT DEFAULT 0,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,next_retry_at DATETIME,last_error VARCHAR(512),payload JSON,is_deleted INT DEFAULT 0,KEY idx_event_outbox_status_next(status,next_retry_at,id),KEY idx_event_outbox_type_time(event_type,created_at))");
        jdbc.execute("CREATE TABLE nx_audit_log (id BIGINT PRIMARY KEY AUTO_INCREMENT,biz_no VARCHAR(96),detail_json JSON,is_deleted INT DEFAULT 0,KEY idx_audit_biz_no(biz_no))");
        jdbc.execute("CREATE TABLE nx_behavior_event_fact (event_id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE nx_event_schema_registry (id BIGINT PRIMARY KEY AUTO_INCREMENT,event_name VARCHAR(128),status VARCHAR(32) DEFAULT 'ACTIVE',is_deleted INT DEFAULT 0,UNIQUE KEY uk_event_schema_name(event_name))");
        jdbc.execute("CREATE TABLE nx_event_consumer_delivery (id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(64),consumer_group VARCHAR(128),status VARCHAR(32),is_deleted INT DEFAULT 0,UNIQUE KEY uk_event_consumer_event_group(event_id,consumer_group))");
        var config = new Configuration(new Environment("bug4-diagnostic-isolated", new JdbcTransactionFactory(),source(SCHEMA)));
        config.addMapper(A4OutboxDiagnosticsMapper.class); factory = new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void cleanup() { new JdbcTemplate(source("")).execute("DROP DATABASE IF EXISTS "+SCHEMA); }
    @BeforeEach void reset() { for (String t : List.of("nx_event_outbox","nx_audit_log","nx_event_schema_registry","nx_event_consumer_delivery")) jdbc.execute("DELETE FROM "+t); }
    String insert(String type, String status, String error) {
        String id=UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO nx_event_outbox(event_id,event_type,status,last_error,payload) VALUES(?,?,?,?,JSON_OBJECT('secret','private-data'))",id,type,status,error); return id;
    }
    @Test void totalsUnresolvedSafeGroupingAndReceiptProjectionAreReadOnly() {
        String legacy=insert("ADMIN_USER_PROFILE_VIEWED","PENDING",null);
        String linked=insert("ADMIN_USER_PROFILE_VIEWED","FAILED","C1_AUDIT_SOURCE_INVALID");
        jdbc.update("INSERT INTO nx_audit_log(biz_no) VALUES(?)", "C1-VIEW-"+linked);
        insert("ADMIN_PHONE_18708173775","PENDING","alice@example.com password-secret");
        insert("UNKNOWN_SECRET_123","PENDING",null);
        insert("ADMIN_USER_LIST_EXPORTED","PUBLISHED",null);
        jdbc.update("INSERT INTO nx_event_consumer_delivery(event_id,consumer_group,status) VALUES(?,?,?)", linked,"private-group","FAILED");
        jdbc.update("INSERT INTO nx_event_consumer_delivery(event_id,consumer_group,status) VALUES(?,?,?)", linked,"private-binding","PENDING_BINDING");
        try(var session=factory.openSession()) {
            var service=new A4OutboxDiagnosticsService(session.getMapper(A4OutboxDiagnosticsMapper.class));
            var result=service.read(null,null,false,"0",25);
            assertThat(result.total()).isEqualTo(4); assertThat(result.unresolved()).isEqualTo(1);
            assertThat(result.groups()).anySatisfy(g -> { assertThat(g.eventType()).isEqualTo("UNREGISTERED_EVENT_TYPE"); assertThat(g.count()).isEqualTo(2); });
            assertThat(result.toString()).doesNotContain("18708173775","alice@example.com","password-secret","private-data","private-group");
            assertThat(result.rows()).anySatisfy(r -> { assertThat(r.eventId()).isEqualTo(linked); assertThat(r.receipts().get(0).status()).isEqualTo("FAILED"); });
            assertThat(result.rows()).anySatisfy(r -> assertThat(r.receipts()).anySatisfy(d -> assertThat(d.status()).isEqualTo("PENDING_BINDING")));
            assertThat(service.read(null,null,true,"0",25).rows()).extracting(r -> r.eventId()).containsExactly(legacy);
            assertThat(service.read("UNREGISTERED_EVENT_TYPE",null,false,"0",25).rows()).hasSize(2);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE status='PENDING'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(retry_count) FROM nx_event_outbox",Integer.class)).isZero();
    }
    @Test void exactFiltersDoNotMatchCaseAccentOrPaddingAliases() {
        insert("ADMIN_USER_LIST_EXPORTED","FAILED","C1_AUDIT_SOURCE_INVALID");
        insert("admin_user_list_exported","FAILED","C1_AUDIT_SOURCE_INVALID ");
        insert("ÁDMIN_USER_LIST_EXPORTED","FAILED",null);
        insert("ADMIN_USER_LIST_EXPORTED ","FAILED",null);
        insert("ADMIN_USER_LIST_EXPORTED","failed",null);
        insert("ADMIN_USER_LIST_EXPORTED","FÁILED",null);
        insert("ADMIN_USER_LIST_EXPORTED","FAILED ",null);
        try(var session=factory.openSession()) {
            var service=new A4OutboxDiagnosticsService(session.getMapper(A4OutboxDiagnosticsMapper.class));
            var result=service.read("ADMIN_USER_LIST_EXPORTED","FAILED",false,"0",25);
            assertThat(result.rows()).hasSize(1); assertThat(result.rows().get(0).errorCode()).isEqualTo("C1_AUDIT_SOURCE_INVALID");
            assertThat(service.read(null,null,false,"0",25).rows()).anySatisfy(r -> assertThat(r.errorCode()).isEqualTo("OTHER_ERROR"));
            var all=service.read(null,null,false,"0",25);
            long a3=jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE is_deleted=0 AND status IN ('PENDING','FAILED')",Long.class);
            assertThat(all.total()).isEqualTo(a3);
            assertThat(all.groups()).anySatisfy(g -> { assertThat(g.eventType()).isEqualTo("ADMIN_USER_LIST_EXPORTED"); assertThat(g.status()).isEqualTo("OTHER"); assertThat(g.count()).isEqualTo(2); });
            assertThat(service.read(null,"OTHER",false,"0",25).rows()).hasSize(2).allSatisfy(r -> assertThat(r.status()).isEqualTo("OTHER"));
            assertThat(all.toString()).doesNotContain("FÁILED", "status=failed");
        }
    }
    @Test void auditAliasesAndMalformedIdsDoNotProduceTrustedLinksOrReceipts() {
        String id=insert("ADMIN_USER_PROFILE_VIEWED","PENDING",null);
        jdbc.update("INSERT INTO nx_audit_log(biz_no) VALUES(?)", "c1-view-"+id);
        jdbc.update("INSERT INTO nx_event_outbox(event_id,event_type,status) VALUES('phone-18708173775','ADMIN_USER_PROFILE_VIEWED','PENDING')");
        jdbc.update("INSERT INTO nx_event_consumer_delivery(event_id,consumer_group,status) VALUES('phone-18708173775','g','SUCCESS')");
        try(var session=factory.openSession()) {
            var result=new A4OutboxDiagnosticsService(session.getMapper(A4OutboxDiagnosticsMapper.class)).read(null,null,false,"0",25);
            assertThat(result.unresolved()).isEqualTo(2);
            assertThat(result.rows().get(1).eventId()).isEqualTo("INVALID_EVENT_ID");
            assertThat(result.rows().get(1).receipts()).isEmpty();
            assertThat(result.toString()).doesNotContain("18708173775");
        }
    }
    @Test void queryTimeoutReturnsFailureWithoutPartialSummaryOrZeroFallback() throws Exception {
        insert("ADMIN_USER_LIST_EXPORTED","FAILED",null);
        try(var holder=source(SCHEMA).getConnection(); var statement=holder.createStatement(); var session=factory.openSession()) {
            statement.execute("LOCK TABLES nx_event_outbox WRITE");
            long started=System.nanoTime();
            try {
                assertThatThrownBy(() -> new A4OutboxDiagnosticsService(session.getMapper(A4OutboxDiagnosticsMapper.class))
                        .read(null,null,false,"0",25)).isInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class);
                long elapsed=(System.nanoTime()-started)/1_000_000;
                System.out.println("A4 diagnostics blocked read cancelled after ms="+elapsed);
                assertThat(elapsed).isLessThan(8000);
            } finally { statement.execute("UNLOCK TABLES"); }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE status='FAILED'",Integer.class)).isEqualTo(1);
    }
    @Test void keysetAndSparsePendingWorkAfterLargePublishedPrefix() {
        jdbc.execute("INSERT INTO nx_event_outbox(event_id,event_type,status) SELECT MD5(CONCAT('prefix',a.n,b.n,c.n,d.n)), 'ADMIN_USER_LIST_EXPORTED','PUBLISHED' FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d");
        var ids=List.of(insert("ADMIN_USER_LIST_EXPORTED","PENDING",null),insert("ADMIN_USER_LIST_EXPORTED","PENDING",null),insert("ADMIN_USER_LIST_EXPORTED","PENDING",null));
        jdbc.execute("ANALYZE TABLE nx_event_outbox");
        System.out.println("A4 diagnostics sparse prefix EXPLAIN: "+jdbc.queryForList("EXPLAIN SELECT id FROM nx_event_outbox o WHERE o.is_deleted=0 AND o.status IN ('PENDING','FAILED') AND o.id>0 ORDER BY o.id LIMIT 26"));
        try(var session=factory.openSession()) {
            var service=new A4OutboxDiagnosticsService(session.getMapper(A4OutboxDiagnosticsMapper.class));
            var first=service.read(null,null,false,"0",2); var second=service.read(null,null,false,first.nextCursor(),2);
            assertThat(first.total()).isEqualTo(3); assertThat(first.rows()).extracting(r -> r.eventId()).containsExactlyElementsOf(ids.subList(0,2));
            assertThat(second.rows()).extracting(r -> r.eventId()).containsExactly(ids.get(2)); assertThat(second.hasMore()).isFalse();
        }
        jdbc.update("UPDATE nx_event_outbox SET status=CASE WHEN MOD(id,2)=0 THEN 'PENDING' ELSE 'FAILED' END");
        long started=System.nanoTime();
        try(var session=factory.openSession()) {
            var result=new A4OutboxDiagnosticsService(session.getMapper(A4OutboxDiagnosticsMapper.class)).read(null,"FAILED",false,"0",25);
            assertThat(result.total()).isEqualTo(10003);
            assertThat(result.groups().stream().mapToLong(g -> g.count()).sum()).isEqualTo(10003);
            assertThat(result.rows()).hasSize(25).allSatisfy(r -> assertThat(r.status()).isEqualTo("FAILED"));
            assertThat(result.hasMore()).isTrue();
            System.out.println("A4 diagnostics 10003 pending/failed full summary+groups+filtered page+receipts elapsed ms="+(System.nanoTime()-started)/1_000_000);
        }
    }
}
