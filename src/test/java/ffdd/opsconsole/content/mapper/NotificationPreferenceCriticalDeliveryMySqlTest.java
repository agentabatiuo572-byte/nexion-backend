package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Exercises the annotated notification mappers against a private disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_NOTIFICATION_DELIVERY_IT", matches = "true")
class NotificationPreferenceCriticalDeliveryMySqlTest {
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    @Test
    void criticalDisclosureIgnoresDisabledPreferencesAndRemainsUserScopedAndIdempotent() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);
            try (var session = session(connection)) {
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                assertThat(campaign.insertDisclosureReackNotifications(
                        "DISCLOSURE-VN-20260911", "VN", "2026.09", List.of("VN"), now)).isEqualTo(1);
                // Replaying the same dispatch keeps the unique (biz_no, user_id) notification singular.
                campaign.insertDisclosureReackNotifications("DISCLOSURE-VN-20260911", "VN", "2026.09", List.of("VN"), now);
                assertThat(rows(connection, "DISCLOSURE-VN-20260911")).isEqualTo(1L);

                assertThat(campaign.markCampaignNotificationsDelivered("DISCLOSURE-VN-20260911", now)).isEqualTo(1);
                assertThat(campaign.markCampaignNotificationsDelivered("DISCLOSURE-VN-20260911", now)).isZero();
                assertThat(campaign.countNotificationsByBizNo("DISCLOSURE-VN-20260911")).isEqualTo(1);
                assertThat(campaign.selectUserNotifications(1L, null, null, 20)).extracting(view -> view.id()).hasSize(1);
                assertThat(campaign.selectUserNotifications(2L, null, null, 20)).isEmpty();
                assertThat(campaign.countUnreadForUser(1L)).isEqualTo(1L);
                assertThat(campaign.countUnreadForUser(2L)).isZero();

                long notificationId = scalar(connection, "SELECT id FROM nx_notification WHERE biz_no='DISCLOSURE-VN-20260911'");
                assertThat(campaign.markUserNotificationRead(2L, notificationId)).isZero();
                assertThat(campaign.countUnreadForUser(1L)).isEqualTo(1L);
                assertThat(campaign.markUserNotificationRead(1L, notificationId)).isEqualTo(1);
                assertThat(campaign.countUnreadForUser(1L)).isZero();
                assertThat(campaign.selectUserNotifications(1L, null, null, 20)).singleElement()
                        .satisfies(view -> assertThat(view.readAt()).isNotNull());
            }
        });
    }

    @Test
    void normalCampaignAndNovaRemainFilteredWhenAllPreferencesAreDisabled() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO nx_nova_channel VALUES ('system',1,0)");
                sql.execute("INSERT INTO nx_nova_template VALUES ('system',0,'PUBLISHED')");
            }
            LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);
            try (var session = session(connection)) {
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                var nova = session.getMapper(NovaSocialRuntimeMapper.class);
                assertThat(campaign.insertCampaignNotifications(
                        "CAMPAIGN-NORMAL-20260911", "system", "normal", "all", 0,
                        "zh", "zh", "vi", "vi", "en", "en", "open", "/normal", now)).isZero();
                assertThat(nova.enqueueBusinessNotifications(
                        "system", "SYSTEM", "event-1", 1L, "NOVA-NORMAL-20260911",
                        "zh", "zh", "vi", "vi", "en", "en", "/nova", now.minusHours(1), now)).isZero();
                assertThat(rows(connection, "CAMPAIGN-NORMAL-20260911")).isZero();
                assertThat(rows(connection, "NOVA-NORMAL-20260911")).isZero();
            }
        });
    }

    @Test
    void criticalCampaignKeepsItsFactAndBulkReadPathWhileAnAlreadyQueuedNormalRowStaysUndelivered() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);
            try (var session = session(connection); var sql = connection.createStatement()) {
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                assertThat(campaign.insertCampaignNotifications(
                        "CAMPAIGN-CRITICAL-20260911", "system", "critical", "vi", 0,
                        "zh", "zh", "vi", "vi", "en", "en", "open", "/critical", now)).isEqualTo(1);
                assertThat(campaign.markCampaignNotificationsDelivered("CAMPAIGN-CRITICAL-20260911", now)).isEqualTo(1);
                long criticalId = scalar(connection, "SELECT id FROM nx_notification WHERE biz_no='CAMPAIGN-CRITICAL-20260911'");
                assertThat(campaign.selectNotificationEventFactsByBizNo(
                        "CAMPAIGN-CRITICAL-20260911", "P1", now))
                        .extracting(fact -> fact.notificationId()).containsExactly(criticalId);
                assertThat(campaign.lockUnreadNotificationEventFacts(1L))
                        .extracting(fact -> fact.notificationId()).containsExactly(criticalId);
                assertThat(campaign.markAllUserNotificationsRead(1L, List.of(criticalId))).isEqualTo(1);
                assertThat(campaign.lockUnreadNotificationEventFacts(1L)).isEmpty();

                sql.execute("INSERT INTO nx_notification (biz_no,user_id,type,priority,title,body,read_flag,push_status,push_attempts,next_push_at,created_at,updated_at,is_deleted) "
                        + "VALUES ('PREQUEUED-NORMAL-20260911',1,'SYSTEM','normal','normal','normal',0,'QUEUED',0,'2026-09-11 12:00:00','2026-09-11 12:00:00','2026-09-11 12:00:00',0)");
                assertThat(campaign.markCampaignNotificationsDelivered("PREQUEUED-NORMAL-20260911", now)).isZero();
                assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification WHERE biz_no='PREQUEUED-NORMAL-20260911' AND push_status='QUEUED'"))
                        .isEqualTo(1L);
            }
        });
    }

    private static org.apache.ibatis.session.SqlSession session(Connection connection) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NotificationCampaignMapper.class);
        configuration.addMapper(NovaSocialRuntimeMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(connection);
    }

    private static void seedUsersAndPreferences(Connection connection) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,language VARCHAR(32),country_code VARCHAR(8),status VARCHAR(32),created_at DATETIME,is_deleted INT)");
            sql.execute("CREATE TABLE nx_user_preference (user_id BIGINT PRIMARY KEY,notify_commission INT,notify_team INT,notify_staking INT,notify_market INT,notify_genesis INT,notify_system INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_notification (id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_no VARCHAR(128),user_id BIGINT,type VARCHAR(32),priority VARCHAR(16),title VARCHAR(128),body VARCHAR(512),cta_label VARCHAR(128),cta_href VARCHAR(256),read_flag INT,read_at DATETIME NULL,push_status VARCHAR(32),pushed_at DATETIME NULL,push_attempts INT,next_push_at DATETIME NULL,created_at DATETIME,updated_at DATETIME,is_deleted INT,UNIQUE KEY uk_notification_biz_user (biz_no,user_id)) ENGINE=InnoDB");
            sql.execute("CREATE TABLE nx_config_item (config_key VARCHAR(128),config_value VARCHAR(128),status INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_nova_channel (channel_key VARCHAR(32) PRIMARY KEY,enabled INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_nova_template (channel_key VARCHAR(32),is_deleted INT,status VARCHAR(32))");
            sql.execute("INSERT INTO nx_user VALUES (1,'vi','VN','ACTIVE','2026-09-01',0),(2,'en','CA','ACTIVE','2026-09-01',0)");
            sql.execute("INSERT INTO nx_user_preference VALUES (1,0,0,0,0,0,0,0),(2,0,0,0,0,0,0,0)");
        }
    }

    private static long rows(Connection connection, String bizNo) throws Exception {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM nx_notification WHERE biz_no=?")) {
            statement.setString(1, bizNo);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Connection connect(String schema) throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        assertThat(endpoint).as("explicit local test-server endpoint").matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}");
        assertThat(endpoint).as("business MySQL port is excluded").doesNotEndWith(":3306");
        return DriverManager.getConnection("jdbc:mysql://" + endpoint + "/" + schema + OPTIONS, "root",
                System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }

    private static void inSchema(SchemaTest test) throws Exception {
        String schema = "nexion_notification_delivery_it_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("nexion_notification_delivery_it_[a-f0-9]{32}")) throw new IllegalStateException("unsafe test schema");
        try (Connection admin = connect("")) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection connection = connect(schema)) {
                test.run(connection);
            } finally {
                admin.createStatement().execute("DROP DATABASE " + schema);
            }
        }
    }

    @FunctionalInterface
    private interface SchemaTest { void run(Connection connection) throws Exception; }
}
