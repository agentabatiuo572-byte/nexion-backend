package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.content.domain.AppNotificationView;
import ffdd.opsconsole.content.infrastructure.MybatisNotificationCampaignRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs the real category WHERE and repository limit+1 contract in a disposable schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_NOTIFICATION_CATEGORY_IT", matches = "true")
class NotificationCategoryPaginationMySqlTest {
    @Test
    void everyAliasAndFallbackPagesBeforeLimitWithoutChangingAccountUnreadOrVisibility() throws Exception {
        String schema = "notification_category_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            sql(admin, "CREATE DATABASE " + schema);
            try (Connection connection = connect(schema)) {
                seedTables(connection);
                var expected = new LinkedHashMap<String, List<Long>>();
                long id = 1;
                for (var group : kinds().entrySet()) {
                    var ids = new ArrayList<Long>();
                    for (String kind : group.getValue()) {
                        insert(connection, id, 1L, kind.toUpperCase(Locale.ROOT), "high", "DELIVERED", 0);
                        ids.add(id++);
                        // Interleaved other-account rows must not supply this account's lookahead.
                        insert(connection, id++, 2L, kind, "high", "DELIVERED", 0);
                    }
                    ids.sort(Comparator.reverseOrder());
                    expected.put(group.getKey(), ids);
                }
                long total = expected.values().stream().mapToLong(List::size).sum();
                insert(connection, id++, 1L, "wallet", "high", "FAILED", 0);
                insert(connection, id++, 1L, "wallet", "high", "DELIVERED", 1);
                // Disabled preferences still hide ordinary rows; critical bypass stays effective.
                insert(connection, id++, 3L, "commission", "high", "DELIVERED", 0);
                insert(connection, id++, 3L, "wallet", "high", "DELIVERED", 0);
                long criticalFinance = id;
                insert(connection, id++, 3L, "wallet", "critical", "DELIVERED", 0);
                long criticalSystem = id;
                insert(connection, id, 3L, "unknown_future", "critical", "DELIVERED", 0);

                Configuration configuration = new Configuration();
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.addMapper(NotificationCampaignMapper.class);
                try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(connection)) {
                    var mapper = session.getMapper(NotificationCampaignMapper.class);
                    var repository = new MybatisNotificationCampaignRepository(mapper, mock(NotificationCapRuleMapper.class));
                    for (var group : expected.entrySet()) {
                        var actual = new ArrayList<Long>();
                        Long cursor = null;
                        int pages = 0;
                        do {
                            var page = repository.pageUserNotifications(1L, cursor, null, 3, group.getKey());
                            assertThat(page.unread()).as("account unread for %s", group.getKey()).isEqualTo(total);
                            actual.addAll(page.items().stream().map(AppNotificationView::id).toList());
                            cursor = page.nextCursor() == null ? null : Long.valueOf(page.nextCursor());
                            if (cursor != null) {
                                assertThat(page.items()).hasSize(3);
                                assertThat(cursor).isEqualTo(page.items().get(2).id());
                            }
                            assertThat(++pages).as("bounded category traversal").isLessThanOrEqualTo(group.getValue().size());
                        } while (cursor != null);
                        assertThat(actual).as("every %s alias exactly once", group.getKey()).containsExactlyElementsOf(group.getValue());
                    }

                    var all = repository.pageUserNotifications(1L, null, null, 100);
                    assertThat(all.items()).hasSize((int) total);
                    assertThat(all.nextCursor()).isNull();
                    assertThat(repository.pageUserNotifications(1L, null, null, 100, "all")).isEqualTo(all);
                    assertThat(mapper.selectUserNotifications(1L, null, null, 100)).isEqualTo(all.items());
                    assertThat(repository.pageUserNotifications(1L, null, "critical", 4, "finance").items()).isEmpty();
                    assertThat(repository.pageUserNotifications(1L, null, "critical", 4, "finance").unread()).isEqualTo(total);

                    int financePageSize = expected.get("finance").size() / 2;
                    var head = repository.pageUserNotifications(1L, null, "high", financePageSize, "finance");
                    assertThat(head.items()).hasSize(financePageSize);
                    assertThat(head.nextCursor()).isNotNull();
                    var tail = repository.pageUserNotifications(1L, Long.valueOf(head.nextCursor()), "high", financePageSize, "finance");
                    assertThat(tail.items()).hasSize(financePageSize);
                    assertThat(tail.nextCursor()).as("exactly limit is the final page").isNull();
                    long lastId = tail.items().get(financePageSize - 1).id();
                    var empty = repository.pageUserNotifications(1L, lastId, null, 4, "finance");
                    assertThat(empty.items()).isEmpty();
                    assertThat(empty.nextCursor()).isNull();
                    assertThat(repository.pageUserNotifications(99L, null, null, 4, "finance").items()).isEmpty();
                    assertThat(repository.pageUserNotifications(99L, null, null, 4, "finance").unread()).isZero();
                    assertThat(repository.pageUserNotifications(3L, null, null, 1, "finance").items())
                            .extracting(AppNotificationView::id).containsExactly(criticalFinance);
                    assertThat(repository.pageUserNotifications(3L, null, null, 1, "finance").nextCursor()).isNull();
                    assertThat(repository.pageUserNotifications(3L, null, null, 1, "system").items())
                            .extracting(AppNotificationView::id).containsExactly(criticalSystem);
                    assertThat(repository.pageUserNotifications(3L, null, null, 1, "system").unread()).isEqualTo(2);
                }
            } finally {
                sql(admin, "DROP DATABASE " + schema);
            }
        }
    }

    @Test
    void clearReadPreservesCriticalUnreadOtherAccountsPermanentReceiptsAndOriginalOrder() throws Exception {
        String schema = "notification_clear_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            sql(admin, "CREATE DATABASE " + schema);
            try (Connection connection = connect(schema)) {
                seedTables(connection);
                insert(connection, 1, 1, "wallet", "high", "READ", 0);
                insert(connection, 2, 1, "device", "normal", "READ", 0);
                insert(connection, 3, 1, "team", "low", "READ", 0);
                insert(connection, 4, 1, "wallet", "critical", "READ", 0);
                insert(connection, 5, 1, "system", "CRITICAL", "READ", 0);
                insert(connection, 6, 1, "system", "critical", "DELIVERED", 0);
                insert(connection, 7, 1, "wallet", "high", "DELIVERED", 0);
                insert(connection, 8, 2, "wallet", "high", "READ", 0);
                insert(connection, 9, 2, "system", "critical", "READ", 0);
                insert(connection, 10, 1, "wallet", null, "READ", 0);
                insert(connection, 11, 1, "system", " Critical ", "READ", 0);
                insert(connection, 12, 1, "wallet", "high", "READ", 1);
                insert(connection, 13, 1, "system", "\tcritical\t", "READ", 0);
                insert(connection, 14, 1, "system", "\u00a0critical\u00a0", "READ", 0);
                insert(connection, 15, 1, "system", "\ufeffcritical\ufeff", "READ", 0);
                sql(connection, "UPDATE nx_notification SET read_flag=1,read_at='2026-10-30 00:00:00' WHERE id IN (1,2,3,4,5,8,9,10,11,12,13,14,15)");
                sql(connection, "UPDATE nx_notification SET cta_href='/pages/me/withdraw-detail?id=100' WHERE id IN (1,4)");
                sql(connection, "CREATE TABLE nx_notification_action_receipt(id BIGINT PRIMARY KEY,notification_id BIGINT,user_id BIGINT,action VARCHAR(32),route VARCHAR(255),idempotency_key VARCHAR(128),is_deleted INT)");
                sql(connection, "INSERT INTO nx_notification_action_receipt VALUES(1,1,1,'cta','/pages/me/withdraw-detail?id=100','receipt-normal',0),(2,4,1,'cta','/pages/me/withdraw-detail?id=100','receipt-critical',0)");
                sql(connection, "CREATE TABLE nx_withdrawal_order(id BIGINT PRIMARY KEY,user_id BIGINT,amount DECIMAL(18,2),status VARCHAR(32),is_deleted INT)");
                sql(connection, "INSERT INTO nx_withdrawal_order VALUES(100,1,125.50,'PENDING',0)");

                Configuration configuration = new Configuration();
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.addMapper(NotificationCampaignMapper.class);
                try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(connection)) {
                    var mapper = session.getMapper(NotificationCampaignMapper.class);
                    var repository = new MybatisNotificationCampaignRepository(mapper, mock(NotificationCapRuleMapper.class));
                    assertThat(repository.clearReadNotifications(1L)).as("only this account's four read non-critical rows").isEqualTo(4);
                    assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification WHERE id IN (1,2,3,10) AND is_deleted=1")).isEqualTo(4);
                    assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification WHERE id IN (4,5,11,13,14,15) AND is_deleted=0 AND read_flag=1")).as("every App-accepted critical priority survives").isEqualTo(6);
                    assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification WHERE id IN (6,7) AND is_deleted=0 AND read_flag=0")).as("unread rows survive").isEqualTo(2);
                    assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND is_deleted=0")).isEqualTo(2);
                    assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification_action_receipt WHERE is_deleted=0")).isEqualTo(2);
                    assertThat(mapper.findNotificationActionReceipt("receipt-normal").notificationId()).isEqualTo(1L);
                    assertThat(mapper.findNotificationActionReceipt("receipt-critical").route()).isEqualTo("/pages/me/withdraw-detail?id=100");
                    assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_withdrawal_order WHERE id=100 AND user_id=1 AND amount=125.50 AND status='PENDING' AND is_deleted=0")).isEqualTo(1);
                    assertThat(repository.clearReadNotifications(1L)).as("repeated clear is idempotent").isZero();
                }
            } finally {
                sql(admin, "DROP DATABASE " + schema);
            }
        }
    }

    private static Map<String, List<String>> kinds() {
        var result = new LinkedHashMap<String, List<String>>();
        result.put("finance", List.of("wallet", "payment_method", "withdrawal", "order", "commission", "staking", "nova_commission", "funds_result",
                " wallet ", "\tWaLLeT\r\n", "\u00a0wallet\u00a0", "\ufeffwallet\ufeff", "\u1680wallet\u3000", "\u2000wallet\u2028\u2029\u202f\u205f"));
        result.put("device", List.of("device", "dailysummary", "nova_dailysummary", "nova_upgrade", "nova_tradein", "device_expiry", "device_alert"));
        result.put("team", List.of("team", "nova_team", "nova_team_event"));
        result.put("rewards", List.of("marketing", "market", "nova_market", "nova_staking", "nova_staking_event", "nova_market_event", "genesis", "nova_genesis", "reward", "nova_eventclaim", "nova_quest", "nova_tasklockmonthly"));
        result.put("system", List.of("system", "nova_welcome", "support_reply", "unknown_future", "wállét", "\u0085wallet\u0085"));
        return result;
    }

    private static void seedTables(Connection c) throws Exception {
        sql(c, "CREATE TABLE nx_user_preference(user_id BIGINT PRIMARY KEY,notify_commission INT,notify_team INT,notify_staking INT,notify_market INT,notify_genesis INT,notify_system INT,is_deleted INT)");
        sql(c, "INSERT INTO nx_user_preference VALUES(3,0,0,0,0,0,0,0)");
        sql(c, "CREATE TABLE nx_notification(id BIGINT PRIMARY KEY,user_id BIGINT,type VARCHAR(32),priority VARCHAR(16),title VARCHAR(128),body VARCHAR(512),cta_label VARCHAR(128),cta_href VARCHAR(256),read_flag INT DEFAULT 0,read_at DATETIME,created_at DATETIME,updated_at DATETIME,push_status VARCHAR(32),is_deleted INT,KEY idx_user_id(user_id,id)) ENGINE=InnoDB");
    }

    private static void insert(Connection c, long id, long userId, String kind, String priority, String status, int deleted) throws Exception {
        try (var statement = c.prepareStatement("INSERT INTO nx_notification(id,user_id,type,priority,title,body,created_at,updated_at,push_status,is_deleted) VALUES(?,?,?,?,'fixture','fixture',NOW(),NOW(),?,?)")) {
            statement.setLong(1, id);
            statement.setLong(2, userId);
            statement.setString(3, kind);
            statement.setString(4, priority);
            statement.setString(5, status);
            statement.setInt(6, deleted);
            statement.executeUpdate();
        }
    }

    private static Connection connect(String schema) throws Exception {
        return DriverManager.getConnection("jdbc:mysql://" + System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT") + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root",
                System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }

    private static void sql(Connection c, String sql) throws Exception {
        try (var statement = c.createStatement()) { statement.execute(sql); }
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (var statement = c.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
