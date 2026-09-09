package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual production mapper in a disposable schema, with no application-user writes. */
@EnabledIfEnvironmentVariable(named = "NEXION_INBOX_IT", matches = "true")
class AppConversationInboxMySqlTest {
    @Test void personalMonotonicDismissalsPreservePublicHistoryAndDoNotSwallowNewMessages() throws Exception {
        String schema = "nexion_inbox_it_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("nexion_inbox_it_[0-9a-f]{32}")) throw new IllegalStateException("unsafe schema");
        String base = "jdbc:mysql://127.0.0.1:3306/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String password = System.getenv("NEXION_DB_PASSWORD");
        try (Connection admin = DriverManager.getConnection(base + options, "root", password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try {
                var ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", base + schema + options, "root", password);
                var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
                config.addMapper(AppConversationInboxMapper.class);
                config.addMapper(ConversationMapper.class);
                try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                    var mapper = session.getMapper(AppConversationInboxMapper.class);
                    var sql = session.getConnection().createStatement();
                    sql.execute("CREATE TABLE nx_conversation (conversation_no VARCHAR(40) PRIMARY KEY, user_id BIGINT, conversation_type VARCHAR(16), status VARCHAR(16), unread_count INT, is_deleted INT)");
                    sql.execute("CREATE TABLE nx_conversation_message (id BIGINT PRIMARY KEY, conversation_no VARCHAR(40), sender_type VARCHAR(16), content VARCHAR(100), is_deleted INT)");
                    sql.execute("ALTER TABLE nx_conversation ADD id BIGINT AUTO_INCREMENT UNIQUE, ADD owner_agent_id VARCHAR(40), ADD owner_agent_name VARCHAR(100), ADD last_message VARCHAR(100), ADD last_message_at DATETIME, ADD updated_at DATETIME DEFAULT CURRENT_TIMESTAMP, ADD created_at DATETIME DEFAULT CURRENT_TIMESTAMP, ADD version BIGINT DEFAULT 1");
                    sql.execute("CREATE TABLE nx_conversation_transfer (conversation_no VARCHAR(40), status VARCHAR(20), is_deleted INT, from_agent_id VARCHAR(40), from_agent_name VARCHAR(100), to_type VARCHAR(20), to_id VARCHAR(40), to_name VARCHAR(100), reason VARCHAR(100), transferred_at DATETIME)");
                    sql.execute("INSERT INTO nx_conversation (conversation_no,user_id,conversation_type,status,unread_count,is_deleted) VALUES ('CV-A',7,'advisor','OPEN',3,0),('CV-S',8,'support','CLOSED',2,0),('CV-D',7,'support','OPEN',1,1)");
                    sql.execute("INSERT INTO nx_conversation_message VALUES (11,'CV-A','user','question',0),(12,'CV-A','agent','answer',0),(13,'CV-A','system','private note',0),(14,'CV-A','agent','deleted',1),(21,'CV-S','agent','answer',0),(31,'CV-D','agent','answer',0)");
                    String migration = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/migrations/20260909_app_conversation_dismissal.sql"));
                    sql.execute(migration); sql.execute(migration);
                    assertThat(mapper.publicMessageExists(7L,"CV-A",12L)).isTrue();
                    assertThat(mapper.publicMessageExists(8L,"CV-A",12L)).isFalse();
                    assertThat(mapper.publicMessageExists(7L,"CV-A",13L)).isFalse();
                    assertThat(mapper.publicMessageExists(7L,"CV-A",14L)).isFalse();
                    assertThat(mapper.publicMessageExists(7L,"CV-A",21L)).isFalse();
                    assertThat(mapper.publicMessageExists(7L,"CV-D",31L)).isFalse();
                    mapper.dismiss(7L,"CV-A",12L); mapper.dismiss(7L,"CV-A",11L); mapper.dismiss(7L,"CV-A",12L);
                    mapper.dismiss(8L,"CV-S",21L);
                    mapper.dismiss(8L,"CV-A",12L); mapper.dismiss(7L,"CV-A",999L); mapper.dismiss(7L,"CV-A",13L);
                    assertThat(mapper.list(7L)).containsExactly(new AppConversationInboxMapper.Dismissal("CV-A",12L));
                    assertThat(mapper.list(8L)).containsExactly(new AppConversationInboxMapper.Dismissal("CV-S",21L));
                    assertThat(mapper.list(9L)).isEmpty();
                    var conversations = session.getMapper(ConversationMapper.class);
                    assertThat(conversations.findByConversationNo("CV-A").lastPublicMessageId()).isEqualTo(12L);
                    assertThat(conversations.pageConversations(null,null,null,null,7L,false,null,true,100,0))
                            .singleElement().satisfies(row -> assertThat(row.lastPublicMessageId()).isEqualTo(12L));
                    // New public message commits before a delayed dismissal using the previously displayed cursor.
                    sql.execute("INSERT INTO nx_conversation_message VALUES (15,'CV-A','agent','new reply',0)");
                    mapper.dismiss(7L,"CV-A",12L);
                    assertThat(mapper.find(7L,"CV-A").throughMessageId()).isLessThan(15L);
                    assertThat(conversations.pageConversations(null,null,null,null,7L,false,null,true,100,0))
                            .singleElement().satisfies(row -> assertThat(row.lastPublicMessageId()).isEqualTo(15L));
                    try (var rows = sql.executeQuery("SELECT status,unread_count FROM nx_conversation WHERE conversation_no='CV-A'")) {
                        assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("OPEN"); assertThat(rows.getInt(2)).isEqualTo(3);
                    }
                    try (var rows = sql.executeQuery("SELECT COUNT(*) FROM nx_conversation_message")) {
                        rows.next(); assertThat(rows.getInt(1)).isEqualTo(7);
                    }
                    mapper.dismiss(7L,"CV-A",15L);
                    assertThat(mapper.find(7L,"CV-A").throughMessageId()).isEqualTo(15L);
                }
            } finally { admin.createStatement().execute("DROP DATABASE " + schema); }
        }
    }
}
