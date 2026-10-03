package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.AppNotificationService;
import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import ffdd.opsconsole.content.mapper.NotificationCapRuleMapper;
import ffdd.opsconsole.content.web.AppNotificationController;
import ffdd.opsconsole.shared.api.ApiResultHttpStatusAdvice;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real production notification SQL and HTTP serialization in a private disposable schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_NOTIFICATION_DELIVERY_IT", matches = "true")
class AppNotificationUnreadSummaryMySqlTest {
    @Test
    void summaryIncludesOlderKindsBeyondOneHundredAndIgnoresCursorLimitAndPriority() throws Exception {
        inSchema(runtime -> {
            runtime.seed(1, 1, "FuTuRe_KiNd", "high", "SUCCESS", false, false);
            runtime.seed(2, 1, "DEVICE", "normal", "SENT", false, false);
            for (int id = 3; id <= 152; id++) {
                runtime.seed(id, 1, id % 2 == 0 ? "WALLET" : "wallet", "normal", "DELIVERED", false, false);
            }
            var first = runtime.page(1, null, null, 100);
            assertThat(first.path("items").size()).isEqualTo(100);
            assertThat(first.path("nextCursor").asText()).isEqualTo("53");
            assertSummary(first, Map.of("wallet", 150L, "device", 1L, "future_kind", 1L));
            var second = runtime.page(1, "53", null, 100);
            assertThat(second.path("items").size()).isEqualTo(52);
            assertThat(second.path("nextCursor").isNull()).isTrue();
            assertSummary(second, Map.of("wallet", 150L, "device", 1L, "future_kind", 1L));
            var filtered = runtime.page(1, null, "high", 1);
            assertThat(filtered.path("items").size()).isEqualTo(1);
            assertSummary(filtered, Map.of("wallet", 150L, "device", 1L, "future_kind", 1L));
            assertSummary(runtime.page(1, "1", null, 1), Map.of("wallet", 150L, "device", 1L, "future_kind", 1L));
        });
    }

    @Test
    void summaryMatchesVisibilityPreferencesDeliveryDeletionAndAccountIsolation() throws Exception {
        inSchema(runtime -> {
            runtime.jdbc.update("INSERT INTO nx_user_preference(user_id,notify_commission) VALUES(1,0)");
            runtime.seed(1, 1, "COMMISSION", "normal", "DELIVERED", false, false);
            runtime.seed(2, 1, "NOVA_COMMISSION", "normal", "DELIVERED", false, false);
            runtime.seed(3, 1, "COMMISSION", "critical", "DELIVERED", false, false);
            runtime.seed(4, 1, "WALLET", "normal", "QUEUED", false, false);
            runtime.seed(5, 1, "WALLET", "normal", "DELIVERED", false, true);
            runtime.seed(6, 1, "WALLET", "normal", "READ", true, false);
            runtime.seed(7, 1, "UNKNOWN", "normal", "READ", false, false);
            runtime.seed(8, 2, "DEVICE", "normal", "DELIVERED", false, false);
            assertSummary(runtime.page(1, null, null, 100), Map.of("commission", 1L, "unknown", 1L));
            assertSummary(runtime.page(2, null, null, 100), Map.of("device", 1L));
            assertSummary(runtime.page(3, null, null, 100), Map.of());
            runtime.jdbc.update("UPDATE nx_user_preference SET is_deleted=1 WHERE user_id=1");
            assertSummary(runtime.page(1, null, null, 100), Map.of("commission", 2L, "nova_commission", 1L, "unknown", 1L));
        });
    }

    @Test
    void individualReadBulkReadAndClearPersistAcrossFreshRequestsWithoutCrossAccountChanges() throws Exception {
        inSchema(runtime -> {
            runtime.seed(1, 1, "WALLET", "normal", "DELIVERED", false, false);
            runtime.seed(2, 1, "DEVICE", "normal", "DELIVERED", false, false);
            runtime.seed(3, 1, "SYSTEM", "critical", "DELIVERED", false, false);
            runtime.seed(4, 2, "DEVICE", "normal", "DELIVERED", false, false);
            assertThat(runtime.service.markRead(2L, 1L).getCode()).isEqualTo(404);
            assertSummary(runtime.page(1, null, null, 1), Map.of("wallet", 1L, "device", 1L, "system", 1L));
            assertThat(runtime.service.markRead(1L, 1L).getCode()).isZero();
            assertThat(runtime.service.markRead(1L, 1L).getCode()).isZero();
            assertSummary(runtime.page(1, null, null, 1), Map.of("device", 1L, "system", 1L));
            assertThat(runtime.service.clearRead(1L).getData()).isEqualTo(1);
            assertSummary(runtime.page(1, null, null, 1), Map.of("device", 1L, "system", 1L));
            assertThat(runtime.service.markAllRead(1L).getData()).isEqualTo(2);
            assertThat(runtime.service.markAllRead(1L).getData()).isZero();
            assertSummary(runtime.page(1, null, null, 1), Map.of());
            assertThat(runtime.service.clearRead(1L).getData()).isEqualTo(1);
            var finalPage = runtime.page(1, null, null, 100);
            assertSummary(finalPage, Map.of());
            assertThat(finalPage.path("items").size()).isEqualTo(1);
            assertThat(finalPage.path("items").get(0).path("priority").asText()).isEqualTo("critical");
            assertSummary(runtime.page(2, null, null, 100), Map.of("device", 1L));
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM nx_notification WHERE user_id=1 AND read_flag=1", Integer.class)).isEqualTo(3);
        });
    }

    @Test
    void retentionRunsBeforeSummaryAndRemainsScopedToTheRequestingAccount() throws Exception {
        inSchema(runtime -> {
            runtime.jdbc.update("INSERT INTO nx_notification_cap_rule(tier,cap_label,policy) VALUES('normal','2','test')");
            for (int id = 1; id <= 4; id++) runtime.seed(id, 1, "WALLET", "normal", "DELIVERED", false, false);
            runtime.seed(5, 1, "DEVICE", "low", "DELIVERED", false, false);
            runtime.seed(6, 2, "DEVICE", "low", "DELIVERED", false, false);
            runtime.jdbc.update("UPDATE nx_notification SET created_at=NOW()-INTERVAL 72 HOUR WHERE id IN(5,6)");
            assertSummary(runtime.page(1, null, null, 100), Map.of("wallet", 2L));
            assertThat(runtime.jdbc.queryForObject("SELECT is_deleted FROM nx_notification WHERE id=6", Integer.class)).isZero();
            assertSummary(runtime.page(1, null, null, 100), Map.of("wallet", 2L));
        });
    }

    private static void assertSummary(JsonNode page, Map<String, Long> expected) {
        assertThat(page.path("unreadByKind").isObject()).as("full unreadByKind is always a JSON object").isTrue();
        Map<String, Long> actual = new java.util.HashMap<>();
        page.path("unreadByKind").fields().forEachRemaining(entry -> {
            assertThat(entry.getValue().isIntegralNumber()).isTrue();
            actual.put(entry.getKey(), entry.getValue().longValue());
        });
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(page.path("unread").longValue()).isEqualTo(expected.values().stream().mapToLong(Long::longValue).sum());
    }

    private static void inSchema(SchemaTest test) throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        assertThat(endpoint).matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}").doesNotEndWith(":3306");
        String password = System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
        String schema = "nexion_unread_it_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("nexion_unread_it_[a-f0-9]{32}");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection admin = DriverManager.getConnection("jdbc:mysql://" + endpoint + "/" + options, "root", password)) {
            try (var sql = admin.createStatement()) { sql.execute("CREATE DATABASE " + schema); }
            try {
                test.run(new Runtime("jdbc:mysql://" + endpoint + "/" + schema + options, password));
            } finally {
                try (var sql = admin.createStatement()) { sql.execute("DROP DATABASE " + schema); }
            }
        }
    }

    private static final class Runtime {
        final JdbcTemplate jdbc;
        final AppNotificationService service;
        final MockMvc mvc;
        final ObjectMapper json = new ObjectMapper();

        Runtime(String url, String password) throws Exception {
            var source = new DriverManagerDataSource(url, "root", password);
            jdbc = new JdbcTemplate(source);
            String ddl = Files.readString(Path.of("scripts/schema.sql"));
            for (String table : List.of("nx_notification", "nx_notification_cap_rule", "nx_user_preference")) {
                var create = Pattern.compile("CREATE TABLE IF NOT EXISTS " + table + "\\s*\\([\\s\\S]*?;").matcher(ddl);
                assertThat(create.find()).as(table).isTrue();
                jdbc.execute(create.group());
            }
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,created_at DATETIME,is_deleted INT)");
            jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(128),config_value VARCHAR(128),status INT,is_deleted INT)");
            jdbc.update("INSERT INTO nx_user VALUES(1,NOW(),0),(2,NOW(),0),(3,NOW(),0)");
            var configuration = new MybatisConfiguration(new Environment("notification-unread-it", new SpringManagedTransactionFactory(), source));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(NotificationCampaignMapper.class);
            configuration.addMapper(NotificationCapRuleMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
            var target = new AppNotificationService(new MybatisNotificationCampaignRepository(
                    session.getMapper(NotificationCampaignMapper.class), session.getMapper(NotificationCapRuleMapper.class)),
                    mock(EventOutboxService.class), mock(NovaSocialRuntimeRepository.class));
            var proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            service = (AppNotificationService) proxy.getProxy();
            mvc = MockMvcBuilders.standaloneSetup(new AppNotificationController(service))
                    .setControllerAdvice(new ApiResultHttpStatusAdvice()).build();
        }

        void seed(long id, long userId, String kind, String priority, String pushStatus, boolean read, boolean deleted) {
            jdbc.update("INSERT INTO nx_notification(id,user_id,type,priority,title,body,push_status,read_flag,is_deleted) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, userId, kind, priority, "Notification " + id, "Body", pushStatus, read ? 1 : 0, deleted ? 1 : 0);
        }

        JsonNode page(long userId, String cursor, String priority, int limit) throws Exception {
            var identity = new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of());
            identity.setDetails(Map.of("subjectType", "USER"));
            var request = get("/api/notifications").principal(identity).param("limit", String.valueOf(limit));
            if (cursor != null) request.param("cursor", cursor);
            if (priority != null) request.param("priority", priority);
            var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
            return json.readTree(response.getContentAsString()).path("data");
        }
    }

    @FunctionalInterface
    private interface SchemaTest { void run(Runtime runtime) throws Exception; }
}
