package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import java.sql.DriverManager;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual production mapper against a task-owned local instance and disposable schema. */
@EnabledIfEnvironmentVariable(named = "BUG552_MYSQL_URL", matches = ".+")
class ConversationPendingReplyMySqlTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private long pending(ContentConversationView row) {
        return json.valueToTree(row).path("pendingReplyCount").asLong(-1);
    }

    @Test void eachSegmentKeepsItsOwnReplyCursorAcrossListDetailAndPagination() throws Exception {
        String base = System.getenv("BUG552_MYSQL_URL");
        assertThat(base).matches("jdbc:mysql://127\\.0\\.0\\.1:35552/");
        String schema = "bug552_" + UUID.randomUUID().toString().replace("-", "");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        try (var admin = DriverManager.getConnection(base + options, "root", "")) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try {
                var ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", base + schema + options, "root", "");
                var config = new Configuration(new Environment("bug552", new JdbcTransactionFactory(), ds));
                config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
                config.addMapper(ConversationMapper.class);
                config.addMapper(SupportBindingMapper.class);
                try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true);
                     var sql = session.getConnection().createStatement()) {
                    sql.execute("CREATE TABLE nx_conversation (id BIGINT PRIMARY KEY,conversation_no VARCHAR(64) UNIQUE,user_id BIGINT,conversation_type VARCHAR(16),status VARCHAR(16),unread_count INT DEFAULT 0,last_message VARCHAR(100),last_message_at DATETIME,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,version BIGINT DEFAULT 1,archived BOOLEAN DEFAULT FALSE,is_deleted INT DEFAULT 0)");
                    sql.execute("CREATE TABLE nx_conversation_message (id BIGINT PRIMARY KEY,conversation_no VARCHAR(64),sender_type VARCHAR(16),content VARCHAR(100),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,is_deleted INT DEFAULT 0,KEY idx_conversation_message_no_time(conversation_no,created_at))");
                    sql.execute("CREATE TABLE nx_support_reply_cursor (conversation_no VARCHAR(64) PRIMARY KEY,through_message_id BIGINT NOT NULL,reply_message_id BIGINT NOT NULL)");
                    sql.execute("CREATE TABLE nx_support_agent_user_assignment (user_id BIGINT,agent_admin_id BIGINT,status VARCHAR(16),is_deleted INT)");
                    sql.execute("CREATE TABLE nx_admin (id BIGINT PRIMARY KEY,nickname VARCHAR(64),username VARCHAR(64),status INT DEFAULT 1,is_deleted INT DEFAULT 0)");
                    sql.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,is_deleted INT DEFAULT 0)");
                    sql.execute("CREATE TABLE nx_admin_role_relation (admin_id BIGINT,role_id BIGINT,is_deleted INT DEFAULT 0)");
                    sql.execute("CREATE TABLE nx_admin_role (id BIGINT PRIMARY KEY,role_code VARCHAR(32),status INT DEFAULT 1,is_deleted INT DEFAULT 0)");
                    sql.execute("INSERT INTO nx_user(id) VALUES (7),(8)");
                    sql.execute("INSERT INTO nx_admin(id) VALUES (12)");
                    sql.execute("INSERT INTO nx_admin_role(id,role_code) VALUES (1,'SUPERADMIN')");
                    sql.execute("INSERT INTO nx_admin_role_relation(admin_id,role_id) VALUES (12,1)");
                    sql.execute("CREATE TABLE nx_conversation_transfer (conversation_no VARCHAR(64),status VARCHAR(16),is_deleted INT,from_agent_id VARCHAR(40),from_agent_name VARCHAR(100),to_type VARCHAR(20),to_id VARCHAR(40),to_name VARCHAR(100),reason VARCHAR(100),transferred_at DATETIME)");
                    sql.execute("CREATE TABLE nx_conversation_timeout_event (conversation_no VARCHAR(64),event_type VARCHAR(16),created_at DATETIME)");
                    sql.execute("INSERT INTO nx_conversation(id,conversation_no,user_id,conversation_type,status,archived,is_deleted) VALUES (1,'CV-REPLIED',7,'support','CLOSED',0,0),(2,'CV-HISTORY',7,'support','CLOSED',1,0),(3,'CV-CROSS-REPLIED',7,'support','CLOSED',0,0),(4,'CV-EMPTY',7,'support','OPEN',0,0),(5,'CV-OTHER',8,'support','OPEN',0,0),(6,'CV-DELETED',7,'support','CLOSED',0,1)");
                    sql.execute("INSERT INTO nx_conversation_message(id,conversation_no,sender_type,content,is_deleted) VALUES (10,'CV-REPLIED','user','answered',0),(11,'CV-REPLIED','agent','service reply',0),(12,'CV-HISTORY','user','still pending',0),(13,'CV-HISTORY','user','deleted',1),(14,'CV-HISTORY','system','system',0),(15,'CV-HISTORY','agent','maintenance not service',0),(16,'CV-CROSS-REPLIED','user','handled by explicit cross segment target',0),(17,'CV-OTHER','user','unrelated customer',0),(18,'CV-REPLIED','internal','private note',0)");
                    sql.execute("INSERT INTO nx_support_reply_cursor VALUES ('CV-REPLIED',10,11),('CV-CROSS-REPLIED',16,15)");
                    var mapper = session.getMapper(ConversationMapper.class);
                    var authority = session.getMapper(SupportBindingMapper.class);
                    var page = mapper.pageConversations(null,null,null,null,7L,false,null,true,100,0);
                    assertThat(page).hasSize(4);
                    for (var row : page) assertThat(pending(row)).as(row.conversationNo()).isEqualTo(authority.pendingReplies(row.conversationNo()));
                    assertThat(pending(mapper.findByConversationNo("CV-REPLIED"))).isZero();
                    assertThat(pending(mapper.findCurrentByConversationNo("CV-HISTORY"))).isEqualTo(1);
                    assertThat(pending(mapper.findByConversationNo("CV-CROSS-REPLIED"))).isZero();
                    assertThat(mapper.findByConversationNo("CV-DELETED")).isNull();
                    assertThat(mapper.countConversations(null,null,null,7L,null,false)).isEqualTo(4);
                    assertThat(mapper.pageConversations(null,null,null,null,7L,false,null,true,1,2)).singleElement()
                            .satisfies(row -> { assertThat(row.conversationNo()).isEqualTo("CV-HISTORY"); assertThat(pending(row)).isEqualTo(1); assertThat(row.archived()).isTrue(); });
                    var scope = new ReadScope(12L, ReadMode.ALL, null, null);
                    assertThat(mapper.pageConversationsScoped(null,null,null,null,7L,false,null,true,1,2,null,scope)).singleElement()
                            .satisfies(row -> { assertThat(row.conversationNo()).isEqualTo("CV-HISTORY"); assertThat(pending(row)).isEqualTo(1); });
                    assertThat(mapper.countConversationsScoped(null,null,null,7L,null,false,null,scope)).isEqualTo(4);
                    assertThat(mapper.pageConversationsScoped(null,null,null,null,7L,false,null,true,100,0,null,null)).isEmpty();
                    sql.execute("UPDATE nx_admin SET status=0 WHERE id=12");
                    assertThat(mapper.pageConversationsScoped(null,null,null,null,7L,false,null,true,100,0,null,scope)).isEmpty();
                    sql.execute("UPDATE nx_admin SET status=1 WHERE id=12");
                    sql.execute("UPDATE nx_conversation SET status='TRANSFERRED' WHERE conversation_no='CV-HISTORY'");
                    sql.execute("INSERT INTO nx_conversation_transfer(conversation_no,status,is_deleted,to_type,transferred_at) VALUES ('CV-HISTORY','PENDING',0,'agent','2026-01-01')");
                    assertThat(mapper.overdueTransferredConversations(java.time.LocalDateTime.of(2026,1,2,0,0),10)).singleElement()
                            .satisfies(row -> { assertThat(row.conversationNo()).isEqualTo("CV-HISTORY"); assertThat(pending(row)).isEqualTo(1); });
                    try (var rs = sql.executeQuery("EXPLAIN SELECT COUNT(*) FROM nx_conversation_message m LEFT JOIN nx_support_reply_cursor r ON r.conversation_no=m.conversation_no WHERE m.conversation_no='CV-HISTORY' AND m.is_deleted=0 AND m.sender_type='user' AND m.id>COALESCE(r.through_message_id,0)")) {
                        while (rs.next()) {
                            if ("m".equals(rs.getString("table"))) assertThat(rs.getString("possible_keys")).contains("idx_conversation_message_no_time");
                            if ("r".equals(rs.getString("table"))) assertThat(rs.getString("key")).isEqualTo("PRIMARY");
                        }
                    }
                    sql.execute("INSERT INTO nx_conversation_message(id,conversation_no,sender_type,content) VALUES (19,'CV-REPLIED','user','new question')");
                    assertThat(pending(mapper.findByConversationNo("CV-REPLIED"))).isEqualTo(1);
                    sql.execute("UPDATE nx_support_reply_cursor SET through_message_id=19,reply_message_id=20 WHERE conversation_no='CV-REPLIED'");
                    assertThat(pending(mapper.findByConversationNo("CV-REPLIED"))).isZero();
                    assertThat(pending(mapper.findByConversationNo("CV-HISTORY"))).isEqualTo(1);
                    try (var rs = sql.executeQuery("SELECT COUNT(*) FROM nx_conversation_message")) { rs.next(); assertThat(rs.getLong(1)).isEqualTo(10); }
                }
            } finally { admin.createStatement().execute("DROP DATABASE " + schema); }
        }
    }
}
