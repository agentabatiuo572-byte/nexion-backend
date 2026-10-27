package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.*;

import ffdd.opsconsole.content.application.AppConversationVisibilityService;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.infrastructure.MybatisConversationRepository;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.mockito.Mockito.mock;

/** Explicit disposable loopback MySQL only. Never connects to the default application database. */
@EnabledIfEnvironmentVariable(named = "R20_MYSQL_PORT", matches = "[0-9]+")
class AppConversationClearSqlMySqlTest {
    @Test void persistedBoundaryFiltersAllAppSqlButPreservesStaffBytesAndPostClearMessages() throws Exception {
        int port = Integer.parseInt(System.getenv("R20_MYSQL_PORT"));
        if (port <= 1024 || port == 3306) throw new IllegalArgumentException("Disposable MySQL port required");
        String schema = "r20_clear_" + UUID.randomUUID().toString().replace("-", "");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String password = Objects.requireNonNullElse(System.getenv("R20_MYSQL_PASSWORD"), "");
        try (var admin = DriverManager.getConnection(base + options, "root", password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            try {
                var ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", base + schema + options, "root", password);
                var config = new Configuration(new Environment("isolated-r20", new JdbcTransactionFactory(), ds));
                config.addMapper(AppConversationClearMapper.class);
                config.addMapper(ConversationMessageMapper.class);
                config.addMapper(ConversationMapper.class);
                config.addMapper(SupportAttachmentMapper.class);
                var factory = new SqlSessionFactoryBuilder().build(config);
                String before;
                try (var session = factory.openSession(true)) {
                    var sql = session.getConnection().createStatement();
                    schema(sql);
                    var migration = Files.readString(Path.of("scripts/migrations/20261027_app_conversation_user_clear.sql"));
                    // Installation is replay-safe and leaves messages byte-identical.
                    for (int round = 0; round < 2; round++) for (String statement : migration.split(";"))
                        if (!statement.isBlank()) sql.execute(statement);
                    sql.execute("INSERT INTO nx_conversation(id,conversation_no,user_id,owner_agent_id,owner_agent_name) VALUES "
                            + "(1,'CV-A',42,'7','same name'),(2,'CV-B',42,'7','same name'),(3,'CV-OTHER',42,'8','same name'),"
                            + "(4,'CV-FOREIGN',99,'7','same name'),(5,'CV-EMPTY',42,'9','empty')");
                    sql.execute("INSERT INTO nx_conversation_message(id,conversation_id,conversation_no,sender_id,sender_type,sender_name,content) VALUES "
                            + "(10,1,'CV-A',7,'agent','Agent','old original A'),(20,2,'CV-B',7,'agent','Agent','old original B'),"
                            + "(21,2,'CV-B',7,'system','Staff','staff note'),(25,3,'CV-OTHER',8,'agent','Agent','other staff'),"
                            + "(26,4,'CV-FOREIGN',7,'agent','Agent','other account')");
                    before = originalDigest(sql);
                    var clear = session.getMapper(AppConversationClearMapper.class);
                    assertThat(clear.countVisible(42L, null)).isEqualTo(4);
                    assertThat(clear.peer(42L, "CV-A").peerKey()).isEqualTo("agent:7");
                    assertThat(clear.peer(99L, "CV-A")).isNull();
                    assertThat(clear.lockPeer(42L, "agent:7")).extracting(AppConversationClearMapper.PeerRow::conversationNo)
                            .containsExactly("CV-A", "CV-B");
                    clear.advancePeer(42L, "agent:7", 20);
                    clear.advanceMember(42L, "agent:7", "CV-A", 20);
                    clear.advanceMember(42L, "agent:7", "CV-B", 20);
                    clear.advancePeer(42L, "agent:9", 0);
                    clear.advanceMember(42L, "agent:9", "CV-EMPTY", 0);
                }
                // Brand-new DB/session objects, no in-memory dismissal or local storage.
                try (var session = factory.openSession(true)) {
                    var clear = session.getMapper(AppConversationClearMapper.class);
                    var messages = session.getMapper(ConversationMessageMapper.class);
                    var headers = session.getMapper(ConversationMapper.class);
                    var media = session.getMapper(SupportAttachmentMapper.class);
                    var sql = session.getConnection().createStatement();
                    var repository = new MybatisConversationRepository(headers, messages, mock(SupportOwnershipService.class));
                    var app = new AppConversationVisibilityService(clear, repository);
                    assertThat(clear.markers(42L)).hasSize(2);
                    assertThat(clear.markers(99L)).isEmpty();
                    assertThat(clear.countVisible(42L, null)).isEqualTo(1);
                    assertThat(clear.visiblePage(42L, null, null, true, 1, 0)).containsExactly("CV-OTHER");
                    assertThat(clear.visiblePage(42L, null, 3L, true, 1, 0)).isEmpty();
                    assertThat(messages.listUserVisibleByConversationNo("CV-A")).isEmpty();
                    assertThat(messages.selectRecentVisible("CV-B", 101, false)).isEmpty();
                    assertThat(messages.listRecentUserVisibleByConversationNoBefore("CV-A", 99L, 101)).isEmpty();
                    assertThat(messages.selectUnreadUserVisibleAgentMessages("CV-A", false)).isZero();
                    assertThat(messages.listByConversationNo("CV-B")).hasSize(2);
                    assertThat(media.userMessageVisible(42L, 20L)).isFalse();
                    assertThat(media.userMessageVisible(99L, 26L)).isTrue();
                    assertThat(app.project(headers.findByConversationNo("CV-A"), false).lastMessage()).isNull();
                    assertThat(app.project(headers.findByConversationNo("CV-A"), false).userClearedThroughMessageId()).isEqualTo(20L);
                    assertThat(headers.currentCustomerConversation(42L, "7")).isEqualTo("CV-B");
                    assertThat(headers.currentCustomerConversation(42L, "8")).isEqualTo("CV-OTHER");
                    repository.requirePeerForWrite("CV-A", "7");
                    assertThatThrownBy(() -> repository.requirePeerForWrite("CV-A", "8"))
                            .hasMessage("CONVERSATION_PEER_CHANGED");

                    sql.execute("INSERT INTO nx_conversation_message(id,conversation_id,conversation_no,sender_id,sender_type,sender_name,content) "
                            + "VALUES(30,1,'CV-A',7,'agent','Agent','new after clear')");
                    session.clearCache(); // Raw JDBC simulates another request/connection, outside MyBatis writes.
                    assertThat(clear.countVisible(42L, null)).isEqualTo(2);
                    assertThat(clear.visiblePage(42L, null, 3L, true, 1, 0)).containsExactly("CV-A");
                    assertThat(messages.selectRecentVisible("CV-A", 101, true)).extracting(m -> m.id()).containsExactly(30L);
                    assertThat(messages.listRecentUserVisibleByConversationNoBefore("CV-A", 30L, 101)).isEmpty();
                    assertThat(messages.selectUnreadUserVisibleAgentMessages("CV-A", true)).isOne();
                    assertThat(media.userMessageVisible(42L, 30L)).isTrue();
                    assertThat(media.userMessageVisible(99L, 30L)).isFalse();
                    var header = app.project(headers.findByConversationNo("CV-A"), false);
                    assertThat(header.lastMessage()).isEqualTo("new after clear");
                    assertThat(header.lastPublicMessageId()).isEqualTo(30L);
                    assertThat(header.ownerAgentId()).isEqualTo("7");
                    messages.markAgentMessagesReadThrough("CV-A", 30L, "user:42", java.time.LocalDateTime.now(), "OPEN", 3L);
                    try (var receipts = sql.executeQuery("SELECT message_id FROM nx_conversation_message_receipt")) {
                        assertThat(receipts.next()).isTrue(); assertThat(receipts.getLong(1)).isEqualTo(30); assertThat(receipts.next()).isFalse();
                    }
                    // CID boundary survives a changed assignment, while the later message stays visible.
                    sql.execute("UPDATE nx_conversation SET owner_agent_id='8' WHERE conversation_no='CV-A'");
                    session.clearCache();
                    assertThat(clear.peer(42L, "CV-A").throughMessageId()).isEqualTo(20);
                    assertThat(messages.listUserVisibleByConversationNo("CV-A")).extracting(m -> m.id()).containsExactly(30L);
                    clear.advancePeer(42L, "agent:7", 10); // never regresses on reordered/old writes
                    assertThat(clear.markers(42L)).contains(new AppConversationClearMapper.Marker("agent:7", 20));
                    assertThat(originalDigest(sql)).isEqualTo(before);
                }
            } finally { admin.createStatement().execute("DROP DATABASE " + schema); }
        }
    }

    static String originalDigest(Statement sql) throws Exception {
        try (var rows = sql.executeQuery("SELECT SHA2(GROUP_CONCAT(CONCAT(id,':',conversation_no,':',sender_type,':',content) ORDER BY id SEPARATOR '|'),256) FROM nx_conversation_message WHERE id<=26")) {
            rows.next(); return rows.getString(1);
        }
    }

    static void schema(Statement sql) throws Exception {
        sql.execute("CREATE TABLE nx_conversation (id BIGINT PRIMARY KEY,conversation_no VARCHAR(40) UNIQUE,user_id BIGINT,conversation_type VARCHAR(16) DEFAULT 'advisor',status VARCHAR(16) DEFAULT 'OPEN',owner_agent_id VARCHAR(40),owner_agent_name VARCHAR(100),unread_count INT DEFAULT 9,last_message VARCHAR(2000) DEFAULT 'old preview',last_message_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,version BIGINT DEFAULT 3,is_deleted INT DEFAULT 0,archived BOOLEAN DEFAULT 0)");
        sql.execute("CREATE TABLE nx_conversation_message (id BIGINT PRIMARY KEY AUTO_INCREMENT,conversation_id BIGINT,conversation_no VARCHAR(40),sender_id BIGINT,sender_type VARCHAR(16),sender_name VARCHAR(100),content VARCHAR(2000),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,is_deleted INT DEFAULT 0)");
        sql.execute("CREATE TABLE nx_conversation_message_receipt (message_id BIGINT PRIMARY KEY,conversation_no VARCHAR(40),receipt_status VARCHAR(16),read_by VARCHAR(64),read_at DATETIME,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        sql.execute("CREATE TABLE nx_support_human_message (message_id BIGINT,assignment_id BIGINT,kind VARCHAR(20),intent VARCHAR(20),client_message_id VARCHAR(128),attachment_id VARCHAR(40),committed_at DATETIME,sku_id VARCHAR(40),sku_name VARCHAR(100),link_target_json JSON,actor_type VARCHAR(16),actor_id BIGINT)");
        sql.execute("CREATE TABLE nx_admin_account_state (admin_id BIGINT,avatar_asset_id VARCHAR(40),avatar_version BIGINT,is_deleted INT)");
        sql.execute("CREATE TABLE nx_admin (id BIGINT,nickname VARCHAR(100),username VARCHAR(100),is_deleted INT)");
        sql.execute("CREATE TABLE nx_product (product_no VARCHAR(40),name VARCHAR(100),is_deleted INT,store_visible INT,price_usdt DECIMAL(20,2),store_status VARCHAR(16),status VARCHAR(16),estimated_daily_usdt DECIMAL(20,2),daily_nex DECIMAL(20,2))");
        sql.execute("CREATE TABLE nx_support_agent_user_assignment (user_id BIGINT,agent_admin_id BIGINT,status VARCHAR(16),is_deleted INT)");
        sql.execute("CREATE TABLE nx_conversation_transfer (conversation_no VARCHAR(40),from_agent_id VARCHAR(40),from_agent_name VARCHAR(100),to_type VARCHAR(20),to_id VARCHAR(40),to_name VARCHAR(100),reason VARCHAR(200),transferred_at DATETIME,status VARCHAR(20),is_deleted INT)");
        sql.execute("CREATE TABLE nx_conversation_timeout_event (conversation_no VARCHAR(40),event_type VARCHAR(16),created_at DATETIME)");
        sql.execute("CREATE TABLE nx_support_reply_cursor (conversation_no VARCHAR(40),through_message_id BIGINT)");
    }
}
