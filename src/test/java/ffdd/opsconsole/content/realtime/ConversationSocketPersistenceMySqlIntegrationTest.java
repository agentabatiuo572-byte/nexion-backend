package ffdd.opsconsole.content.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import ffdd.opsconsole.content.application.AppSupportService;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.infrastructure.MybatisConversationRepository;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import ffdd.opsconsole.content.web.AppSupportController;
import ffdd.opsconsole.content.web.OpsConversationController;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.user.application.OpsUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestExecutionListeners.MergeMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Opt-in only. It never selects the formal schema: the test creates a random owned
 * schema before its isolated Spring context starts and drops it after every enabled run.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_M3_WS_IT", matches = "true")
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = ConversationSocketPersistenceMySqlIntegrationTest.OwnedSchemaCleanupListener.class,
        mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
@ContextConfiguration(initializers = ConversationSocketPersistenceMySqlIntegrationTest.ContextCaptureInitializer.class)
@SpringBootTest(
        classes = ConversationSocketPersistenceMySqlIntegrationTest.PersistenceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "spring.config.name=m3-ws-it-empty",
                "spring.config.location=optional:classpath:/m3-ws-it-empty.properties",
                "server.address=127.0.0.1",
                "spring.task.scheduling.enabled=false",
                "spring.data.redis.repositories.enabled=false",
                "nexion.cors.development-allowed-origins=http://127.0.0.1:5173",
                "nexion.websocket.allowed-origins=http://127.0.0.1:5173"
        })
class ConversationSocketPersistenceMySqlIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String USER_TOKEN = "m3-it-user-token";
    private static final String ADMIN_TOKEN = "m3-it-admin-token";
    private static OwnedSchema schema;
    private static volatile ConfigurableApplicationContext activeContext;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(WAIT).build();
    private static final AtomicReference<Throwable> commandFailure = new AtomicReference<>();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigurableApplicationContext context;

    private final List<WireClient> clients = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void ownedDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ConversationSocketPersistenceMySqlIntegrationTest::ownedSchemaJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", ConversationSocketPersistenceMySqlIntegrationTest::ownedSchemaPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    @BeforeEach
    void resetOwnedFixture() {
        commandFailure.set(null);
        assertIsolatedSpringConfiguration();
        assertOwnedCatalog();
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(StringRedisTemplate.class)).isEmpty();
        jdbc.update("DELETE FROM nx_conversation_message_receipt");
        jdbc.update("DELETE FROM nx_conversation_message");
        jdbc.update("DELETE FROM nx_conversation_transfer");
        jdbc.update("DELETE FROM nx_conversation");
        jdbc.update("DELETE FROM nx_admin_idempotency_record");
        assertOwnedCatalog();
        jdbc.update("""
                INSERT INTO nx_conversation
                    (conversation_no,user_id,conversation_type,status,owner_agent_id,owner_agent_name,
                     unread_count,last_message,last_message_at,version,is_deleted,created_at,updated_at)
                VALUES ('CV-M3-IT',501,'support','OPEN','901','fixture-advisor',
                        0,'seed',NOW(),0,0,NOW(),NOW())
                """);
        Long id = jdbc.queryForObject("SELECT id FROM nx_conversation WHERE conversation_no='CV-M3-IT'", Long.class);
        jdbc.update("""
                INSERT INTO nx_conversation_message
                    (conversation_id,conversation_no,sender_id,sender_type,sender_name,content,is_deleted,created_at,updated_at)
                VALUES (?,?,?,?,?,?,0,NOW(),NOW())
                """, id, "CV-M3-IT", 901L, "agent", "fixture-advisor", "seed agent message");
        assertOwnedCatalog();
    }

    @AfterEach
    void closeClients() {
        clients.forEach(WireClient::close);
        clients.clear();
    }

    private static synchronized OwnedSchema ownedSchema() {
        if (schema == null) {
            schema = OwnedSchema.prepare();
        }
        return schema;
    }

    private static String ownedSchemaJdbcUrl() {
        return ownedSchema().jdbcUrl();
    }

    private static String ownedSchemaPassword() {
        return ownedSchema().password();
    }

    @Test
    void userReplyIsIdempotentAndAdminReadReachesUserProjection() throws Exception {
        WireClient user = authenticated(USER_TOKEN, "USER");
        WireClient admin = authenticated(ADMIN_TOKEN, "ADMIN");
        user.watch("CV-M3-IT");
        admin.watch("CV-M3-IT");

        Map<String, Object> command = Map.of(
                "type", "command", "operation", "reply", "conversationNo", "CV-M3-IT",
                "idempotencyKey", "m3-user-reply-001",
                "body", Map.of("body", "fixture user reply", "expectedStatus", "OPEN", "expectedVersion", 0));
        user.send(withRequestId(command, "reply-first"));
        JsonNode first = user.await(frame -> "ack".equals(frame.path("type").asText())
                && "reply-first".equals(frame.path("requestId").asText()));
        assertSuccessAck(first);

        user.send(withRequestId(command, "reply-replay"));
        JsonNode replay = user.await(frame -> "ack".equals(frame.path("type").asText())
                && "reply-replay".equals(frame.path("requestId").asText()));
        assertSuccessAck(replay);
        assertEquals(first.path("result").path("data").path("conversationNo").asText(),
                replay.path("result").path("data").path("conversationNo").asText());

        JsonNode adminReplyEvent = awaitStatusEvent(admin, Set.of());
        JsonNode userReplyEvent = awaitStatusEvent(user, Set.of());
        assertEquals(adminReplyEvent.path("eventId").asText(), userReplyEvent.path("eventId").asText());
        JsonNode adminDetail = adminConversation();
        long seedAgentMessageId = messageIdFor(adminDetail, "seed agent message");
        long userMessageId = messageIdForSender(adminDetail, "user", "fixture user reply");
        appendFixtureMessage(501L, "user", "fixture-user", "fixture later unread user sentinel");
        long laterUserMessageId = messageIdForSender(
                adminConversation(), "user", "fixture later unread user sentinel");
        assertThat(laterUserMessageId).isGreaterThan(userMessageId);

        Map<String, Object> read = Map.of(
                "type", "command", "operation", "read", "conversationNo", "CV-M3-IT",
                "body", Map.of("lastSeenMessageId", userMessageId, "expectedStatus", "OPEN", "expectedVersion", 1));
        admin.send(withRequestId(read, "admin-read"));
        assertSuccessAck(admin.await(frame -> "ack".equals(frame.path("type").asText())
                && "admin-read".equals(frame.path("requestId").asText())));
        admin.send(withRequestId(read, "admin-read-replay"));
        assertSuccessAck(admin.await(frame -> "ack".equals(frame.path("type").asText())
                && "admin-read-replay".equals(frame.path("requestId").asText())));

        JsonNode userReadEvent = awaitStatusEvent(user, Set.of(userReplyEvent.path("eventId").asText()));
        assertThat(userReadEvent.path("eventId").asText()).isNotEqualTo(userReplyEvent.path("eventId").asText());
        assertThat(userReadEvent.path("eventId").asText()).isNotEqualTo(adminReplyEvent.path("eventId").asText());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM nx_conversation_message_receipt
                 WHERE conversation_no='CV-M3-IT' AND message_id=? AND receipt_status='read' AND read_by='admin:901'
                """, Integer.class, userMessageId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM nx_conversation_message_receipt
                 WHERE conversation_no='CV-M3-IT' AND message_id=? AND receipt_status='read' AND read_by='admin:901'
                """, Integer.class, laterUserMessageId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM nx_conversation_message_receipt
                 WHERE conversation_no='CV-M3-IT' AND message_id=? AND receipt_status='read' AND read_by='admin:901'
                """, Integer.class, seedAgentMessageId));
        assertEquals("sent", messageFor(adminConversation(), "agent", "seed agent message")
                .path("receiptStatus").asText());
        JsonNode userDetail = userConversation();
        assertEquals("read", messageFor(userDetail, "user", "fixture user reply").path("receiptStatus").asText());
        assertEquals("sent", messageFor(userDetail, "user", "fixture later unread user sentinel")
                .path("receiptStatus").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no='CV-M3-IT' AND sender_type='user' AND content='fixture user reply'",
                Integer.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM nx_conversation WHERE conversation_no='CV-M3-IT'", Long.class));
    }

    @Test
    void adminReplyThenUserReadPersistsOneReceiptAndReadback() throws Exception {
        WireClient user = authenticated(USER_TOKEN, "USER");
        WireClient admin = authenticated(ADMIN_TOKEN, "ADMIN");
        user.watch("CV-M3-IT");
        admin.watch("CV-M3-IT");
        appendFixtureMessage(501L, "user", "fixture-user", "fixture admin reply non-agent sentinel");

        admin.send(Map.of(
                "type", "command", "requestId", "admin-reply", "operation", "reply", "conversationNo", "CV-M3-IT",
                "idempotencyKey", "m3-admin-reply-001",
                "body", Map.of("body", "fixture admin reply", "expectedStatus", "OPEN", "expectedVersion", 0,
                        "reason", "fixture reply", "operator", "fixture-advisor")));
        JsonNode reply = admin.await(frame -> "ack".equals(frame.path("type").asText())
                && "admin-reply".equals(frame.path("requestId").asText()));
        assertSuccessAck(reply);
        JsonNode userReplyEvent = awaitStatusEvent(user, Set.of());
        JsonNode adminReplyEvent = awaitStatusEvent(admin, Set.of());

        JsonNode detail = userConversation();
        long agentMessageId = messageIdFor(detail, "fixture admin reply");
        long nonAgentMessageId = messageIdForSender(
                detail, "user", "fixture admin reply non-agent sentinel");
        assertThat(nonAgentMessageId).isLessThan(agentMessageId);
        appendFixtureMessage(9001L, "agent", "fixture-advisor", "fixture later unread agent sentinel");
        long laterAgentMessageId = messageIdFor(userConversation(), "fixture later unread agent sentinel");
        assertThat(laterAgentMessageId).isGreaterThan(agentMessageId);
        user.send(Map.of(
                "type", "command", "requestId", "user-read", "operation", "read", "conversationNo", "CV-M3-IT",
                "body", Map.of("lastSeenMessageId", agentMessageId, "expectedStatus", "OPEN", "expectedVersion", 1)));
        JsonNode read = user.await(frame -> "ack".equals(frame.path("type").asText())
                && "user-read".equals(frame.path("requestId").asText()));
        assertSuccessAck(read);

        user.send(Map.of(
                "type", "command", "requestId", "user-read-replay", "operation", "read", "conversationNo", "CV-M3-IT",
                "body", Map.of("lastSeenMessageId", agentMessageId, "expectedStatus", "OPEN", "expectedVersion", 1)));
        JsonNode replay = user.await(frame -> "ack".equals(frame.path("type").asText())
                && "user-read-replay".equals(frame.path("requestId").asText()));
        assertSuccessAck(replay);

        JsonNode adminReadEvent = awaitStatusEvent(admin, Set.of(adminReplyEvent.path("eventId").asText()));
        assertThat(adminReadEvent.path("eventId").asText()).isNotEqualTo(adminReplyEvent.path("eventId").asText());
        assertThat(adminReadEvent.path("eventId").asText()).isNotEqualTo(userReplyEvent.path("eventId").asText());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM nx_conversation_message_receipt
                 WHERE conversation_no='CV-M3-IT' AND message_id=? AND receipt_status='read' AND read_by='user:501'
                """, Integer.class, agentMessageId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM nx_conversation WHERE conversation_no='CV-M3-IT'", Long.class));
        JsonNode refreshedDetail = userConversation();
        JsonNode adminProjection = adminConversation();
        assertEquals("read", messageFor(refreshedDetail, "agent", "fixture admin reply")
                .path("receiptStatus").asText());
        assertEquals("read", messageFor(adminProjection, "agent", "fixture admin reply")
                .path("receiptStatus").asText());
        assertEquals("sent", messageFor(adminProjection, "agent", "fixture later unread agent sentinel")
                .path("receiptStatus").asText());
        assertEquals("sent", messageFor(refreshedDetail, "user", "fixture admin reply non-agent sentinel")
                .path("receiptStatus").asText());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM nx_conversation_message_receipt
                 WHERE message_id=? AND conversation_no='CV-M3-IT' AND receipt_status='read' AND read_by='user:501'
                """, Integer.class, nonAgentMessageId));
        assertEquals("sent", messageFor(refreshedDetail, "agent", "fixture later unread agent sentinel")
                .path("receiptStatus").asText());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM nx_conversation_message_receipt
                 WHERE message_id=? AND conversation_no='CV-M3-IT' AND receipt_status='read' AND read_by='user:501'
                """, Integer.class, laterAgentMessageId));
    }

    private WireClient authenticated(String token, String audience) throws Exception {
        String ticket = ticket(token, audience);
        WireClient client = connect();
        client.send(Map.of("type", "auth", "ticket", ticket));
        client.await(frame -> "ready".equals(frame.path("type").asText()));
        return client;
    }

    private String ticket(String token, String audience) throws Exception {
        String path = "USER".equals(audience)
                ? "/api/app/support/realtime-ticket"
                : "/api/admin/content/conversations/realtime-ticket";
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Origin", "http://127.0.0.1:5173")
                .timeout(WAIT)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String ticket = json.readTree(response.body()).path("data").path("ticket").asText();
        assertThat(ticket).isNotBlank();
        return ticket;
    }

    private long messageIdFor(JsonNode detail, String content) {
        return messageFor(detail, "agent", content).path("id").asLong();
    }

    private long messageIdForSender(JsonNode detail, String senderType, String content) {
        return messageFor(detail, senderType, content).path("id").asLong();
    }
    private JsonNode awaitStatusEvent(WireClient client, Set<String> previousEventIds) throws InterruptedException {
        return client.await(frame -> "event".equals(frame.path("type").asText())
                && "STATUS".equals(frame.path("eventType").asText())
                && "CV-M3-IT".equals(frame.path("conversationNo").asText())
                && !previousEventIds.contains(frame.path("eventId").asText()));
    }

    private void assertSuccessAck(JsonNode ack) {
        assertThat(ack.path("result").has("code")).as(ack.toString()).isTrue();
        assertThat(ack.path("result").path("code").isIntegralNumber()).as(ack.toString()).isTrue();
        Throwable failure = commandFailure.get();
        if (ack.path("result").path("code").asInt() != 0 && failure != null) {
            throw new AssertionError("M3_WS_IT_COMMAND_FAILURE: " + ack, failure);
        }
        assertEquals(0, ack.path("result").path("code").asInt(), ack.toString());
    }

    private void assertOwnedCatalog() {
        assertEquals(ownedSchema().schema(), jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    private void assertIsolatedSpringConfiguration() {
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("dev");
        assertThat(context.getEnvironment().getProperty("spring.config.location"))
                .isEqualTo("optional:classpath:/m3-ws-it-empty.properties");
        assertThat(context.getEnvironment().getPropertySources().stream()
                .map(org.springframework.core.env.PropertySource::getName))
                .noneMatch(name -> name.contains("application-dev") || name.contains("application.properties"));
    }

    private void appendFixtureMessage(Long senderId, String senderType, String senderName, String content) {
        Long conversationId = jdbc.queryForObject(
                "SELECT id FROM nx_conversation WHERE conversation_no='CV-M3-IT'", Long.class);
        jdbc.update("""
                INSERT INTO nx_conversation_message
                    (conversation_id,conversation_no,sender_id,sender_type,sender_name,content,is_deleted,created_at,updated_at)
                VALUES (?,?,?,?,?,?,0,NOW(),NOW())
                """, conversationId, "CV-M3-IT", senderId, senderType, senderName, content);
        assertOwnedCatalog();
    }

    private JsonNode messageFor(JsonNode detail, String senderType, String content) {
        for (JsonNode message : detail.path("data").path("messages")) {
            if (senderType.equals(message.path("senderType").asText())
                    && content.equals(message.path("content").asText())) {
                return message;
            }
        }
        throw new AssertionError("M3_WS_IT_MESSAGE_NOT_FOUND");
    }

    private JsonNode adminConversation() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/admin/content/conversations/CV-M3-IT"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .header("Origin", "http://127.0.0.1:5173")
                .timeout(WAIT)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private JsonNode userConversation() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/app/support/conversations/CV-M3-IT"))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .header("Origin", "http://127.0.0.1:5173")
                .timeout(WAIT)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private WireClient connect() throws Exception {
        WireClient client = new WireClient(json);
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://127.0.0.1:5173");
        client.session = new StandardWebSocketClient().execute(client, headers,
                URI.create("ws://127.0.0.1:" + port + "/ws/conversations")).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        clients.add(client);
        return client;
    }

    private static Map<String, Object> withRequestId(Map<String, Object> command, String requestId) {
        Map<String, Object> frame = new java.util.LinkedHashMap<>(command);
        frame.put("requestId", requestId);
        return Map.copyOf(frame);
    }

    private static final class WireClient extends TextWebSocketHandler {
        private final ObjectMapper mapper;
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private volatile WebSocketSession session;

        private WireClient(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession ignored, TextMessage message) throws Exception {
            frames.add(mapper.readTree(message.getPayload()));
        }

        void send(Map<String, Object> frame) throws Exception {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
        }

        void watch(String conversationNo) throws Exception {
            send(Map.of("type", "watch", "conversationNo", conversationNo));
        }

        JsonNode await(Predicate<JsonNode> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (JsonNode frame : frames) {
                    if (predicate.test(frame)) {
                        return frame;
                    }
                }
                Thread.sleep(10);
            }
            fail("timed out waiting for frame: " + frames);
            return null;
        }

        void close() {
            try {
                if (session != null && session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {
                // Embedded server can close a rejected test socket first.
            }
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            RedisAutoConfiguration.class,
            RedisReactiveAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @EnableMethodSecurity
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {
            ConversationMapper.class,
            ConversationMessageMapper.class,
            AdminIdempotencyRecordMapper.class
    })
    @Import(ConversationSocketConfig.class)
    static class PersistenceApplication {
        @Bean
        Clock businessClock() {
            return Clock.system(ZoneId.of("Asia/Shanghai"));
        }

        @Bean
        MybatisMetaObjectHandler mybatisMetaObjectHandler(Clock clock) {
            return new MybatisMetaObjectHandler(clock);
        }

        @Bean
        SecurityFilterChain persistenceSecurity(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .addFilterBefore(new FixtureBearerFilter(), AnonymousAuthenticationFilter.class)
                    .build();
        }

        @Bean
        ProductionSupportPathGuard productionSupportPathGuard() {
            return mock(ProductionSupportPathGuard.class);
        }

        @Bean
        ConversationRepository conversationRepository(
                ConversationMapper conversations,
                ConversationMessageMapper messages) {
            return new MybatisConversationRepository(conversations, messages);
        }

        @Bean
        AuditLogService auditLogService() {
            return mock(AuditLogService.class);
        }

        @Bean
        SupportTicketRepository supportTicketRepository() {
            return mock(SupportTicketRepository.class);
        }

        @Bean
        SupportKnowledgeRepository supportKnowledgeRepository() {
            return mock(SupportKnowledgeRepository.class);
        }

        @Bean
        SupportAgentRepository supportAgentRepository() {
            return mock(SupportAgentRepository.class);
        }

        @Bean
        PlatformConfigFacade platformConfigFacade() {
            return mock(PlatformConfigFacade.class);
        }

        @Bean
        CustomerProfileRepository customerProfileRepository() {
            return mock(CustomerProfileRepository.class);
        }

        @Bean
        OpsReadTimeSeedPolicy opsReadTimeSeedPolicy() {
            return mock(OpsReadTimeSeedPolicy.class);
        }

        @Bean
        OpsUserService opsUserService() {
            return mock(OpsUserService.class);
        }

        @Bean
        OpsFinanceService opsFinanceService() {
            return mock(OpsFinanceService.class);
        }

        @Bean
        OpsDeviceService opsDeviceService() {
            return mock(OpsDeviceService.class);
        }

        @Bean
        OpsRiskService opsRiskService() {
            return mock(OpsRiskService.class);
        }

        @Bean
        AdminIdempotencyExpiryTransitionExecutor expiryTransitionExecutor(AdminIdempotencyRecordMapper records) {
            return new AdminIdempotencyExpiryTransitionExecutor(records);
        }

        @Bean
        AdminIdempotencyTransactionExecutor transactionExecutor(
                AdminIdempotencyRecordMapper records,
                ObjectMapper json,
                AdminIdempotencyExpiryTransitionExecutor expiry) {
            return new AdminIdempotencyTransactionExecutor(records, json, expiry);
        }

        @Bean
        AdminIdempotencyService idempotencyService(
                AdminIdempotencyTransactionExecutor executor,
                Clock clock) {
            return new AdminIdempotencyService(executor, clock);
        }

        @Bean
        AppSupportService appSupportService(
                SupportTicketRepository tickets,
                ConversationRepository conversations,
                SupportKnowledgeRepository knowledge,
                AdminIdempotencyService idempotency,
                AuditLogService audit,
                ApplicationEventPublisher events,
                Clock clock,
                ProductionSupportPathGuard guard,
                AdminIdempotencyRecordMapper idempotencyRecords,
                ObjectMapper json,
                SupportAgentRepository agents,
                PlatformConfigFacade config) {
            return new AppSupportService(tickets, conversations, knowledge, idempotency, audit, events, clock,
                    guard, idempotencyRecords, json, agents, config);
        }

        @Bean
        OpsConversationService opsConversationService(
                ConversationRepository conversations,
                SupportTicketRepository tickets,
                ffdd.opsconsole.content.application.OpsSupportAgentService agents,
                PlatformConfigFacade config,
                AuditLogService audit,
                Clock clock,
                OpsReadTimeSeedPolicy seeds,
                OpsUserService users,
                OpsFinanceService finance,
                OpsDeviceService devices,
                OpsRiskService risks,
                CustomerProfileRepository profiles,
                ProductionSupportPathGuard guard) {
            return new OpsConversationService(conversations, tickets, agents, config, audit, clock, seeds,
                    users, finance, devices, risks, profiles, guard);
        }

        @Bean
        ffdd.opsconsole.content.application.OpsSupportAgentService opsSupportAgentService() {
            return mock(ffdd.opsconsole.content.application.OpsSupportAgentService.class);
        }

        @Bean
        AppSupportController appSupportController(AppSupportService service, ProductionSupportPathGuard guard) {
            return new AppSupportController(service, guard);
        }

        @Bean
        OpsConversationController opsConversationController(
                OpsConversationService service,
                ProductionSupportPathGuard guard,
                ApplicationEventPublisher events,
                AdminIdempotencyService idempotency) {
            return new OpsConversationController(service, guard, events, idempotency);
        }

        @Bean
        ConversationAdminReadService adminReadService(
                ConversationRepository conversations,
                ApplicationEventPublisher events,
                ProductionSupportPathGuard guard,
                Clock clock) {
            return new ConversationAdminReadService(conversations, events, guard, clock);
        }

        @Bean
        ConversationAdminReadController conversationAdminReadController(ConversationAdminReadService service) {
            return new ConversationAdminReadController(service);
        }

        @Bean
        ConversationSocketAccess conversationSocketAccess(ConversationRepository conversations) {
            return new FixtureAccess(conversations);
        }

        @Bean
        ConversationSocketTickets conversationSocketTickets(ConversationSocketAccess access) {
            return new ConversationSocketTickets(access);
        }

        @Bean
        ConversationSocketTicketController conversationSocketTicketController(ConversationSocketTickets tickets) {
            return new ConversationSocketTicketController(tickets);
        }

        @Bean
        ConversationSocketCommands conversationSocketCommands(
                AppSupportController app,
                OpsConversationController admin,
                ConversationAdminReadController reads,
                ConversationSocketAccess access,
                ObjectMapper json) {
            return new DiagnosticConversationSocketCommands(app, admin, reads, access, json);
        }

        @Bean
        ConversationSocketHandler conversationSocketHandler(
                ObjectMapper json,
                ConversationSocketTickets tickets,
                ConversationSocketAccess access,
                ConversationSocketCommands commands) {
            return new ConversationSocketHandler(json, tickets, access, commands);
        }
    }

    static final class FixtureBearerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                Authentication auth = FixtureAccess.authentication(header.substring("Bearer ".length()));
                if (auth != null) {
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            chain.doFilter(request, response);
        }
    }

    static final class FixtureAccess extends ConversationSocketAccess {
        private final ConversationRepository conversations;

        FixtureAccess(ConversationRepository conversations) {
            super(null, null, null, null, conversations, null);
            this.conversations = conversations;
        }

        static Authentication authentication(String token) {
            if (USER_TOKEN.equals(token)) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("501", null, List.of());
                auth.setDetails(Map.of("subjectType", "USER", "username", "fixture-user"));
                return auth;
            }
            if (ADMIN_TOKEN.equals(token)) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("901", null, List.of(
                        new SimpleGrantedAuthority("service_m3_read"),
                        new SimpleGrantedAuthority("service_m3_write")));
                auth.setDetails(Map.of("subjectType", "ADMIN", "username", "fixture-advisor"));
                return auth;
            }
            return null;
        }

        @Override
        public Authentication authenticate(String token, String audience) {
            Authentication auth = authentication(token);
            if (auth == null || !audience.equals(((Map<?, ?>) auth.getDetails()).get("subjectType"))) {
                throw new ffdd.opsconsole.shared.exception.BizException(401, "TEST_SOCKET_AUTH_REQUIRED");
            }
            return auth;
        }

        @Override
        public void write(Authentication auth, String audience) {
            if ("ADMIN".equals(audience) && !ConversationSocketAccess.has(auth, "service_m3_write")) {
                throw new ffdd.opsconsole.shared.exception.BizException(403, "SOCKET_PERMISSION_DENIED");
            }
        }

        @Override
        public boolean canRead(Authentication auth, String audience, String conversationNo) {
            return conversations.findByConversationNo(conversationNo)
                    .map(row -> "ADMIN".equals(audience)
                            ? ConversationSocketAccess.has(auth, "service_m3_read")
                            : String.valueOf(row.userId()).equals(auth.getName()))
                    .orElse(false);
        }

        @Override
        public Optional<Participants> participants(String conversationNo) {
            return conversations.findByConversationNo(conversationNo)
                    .map(row -> new Participants(String.valueOf(row.userId()), row.ownerAgentId()));
        }

        @Override
        public java.util.Set<String> presenceKeys(Authentication auth, String audience) {
            return "USER".equals(audience)
                    ? java.util.Set.of("USER:" + auth.getName())
                    : java.util.Set.of("ADMIN:" + auth.getName());
        }
    }

    static final class DiagnosticConversationSocketCommands extends ConversationSocketCommands {
        DiagnosticConversationSocketCommands(
                AppSupportController app,
                OpsConversationController admin,
                ConversationAdminReadController reads,
                ConversationSocketAccess access,
                ObjectMapper json) {
            super(app, admin, reads, access, json);
        }

        @Override
        public ffdd.opsconsole.shared.api.ApiResult<?> execute(
                Authentication auth, String audience, JsonNode frame) {
            try {
                return super.execute(auth, audience, frame);
            } catch (RuntimeException ex) {
                commandFailure.compareAndSet(null, ex);
                throw ex;
            }
        }
    }

    private static void closeActiveContext() {
        ConfigurableApplicationContext current = activeContext;
        try {
            if (current != null && current.isActive()) {
                current.close();
            }
        } finally {
            activeContext = null;
        }
    }

    static final class ContextCaptureInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            activeContext = applicationContext;
            String url = applicationContext.getEnvironment().getProperty("spring.datasource.url");
            String username = applicationContext.getEnvironment().getProperty("spring.datasource.username");
            if (!ownedSchema().jdbcUrl().equals(url) || !"root".equals(username)) {
                throw new IllegalStateException("M3_WS_IT_DATASOURCE_OWNERSHIP_REQUIRED");
            }
        }
    }

    public static final class OwnedSchemaCleanupListener implements TestExecutionListener, org.springframework.core.Ordered {
        @Override
        public int getOrder() {
            // TestContextManager reverses this order for afterTestClass. This runs
            // after DirtiesContextTestExecutionListener (3000) has closed the context.
            return 2999;
        }

        @Override
        public void afterTestClass(TestContext testContext) throws Exception {
            try {
                closeActiveContext();
            } finally {
                if (schema != null) {
                    schema.close();
                }
            }
        }
    }

    static final class OwnedSchema implements AutoCloseable {
        private static final String PREFIX = "nexion_m3_ws_it_";
        private final String schema;
        private final String password;
        private boolean closed;

        private OwnedSchema(String schema, String password) {
            this.schema = schema;
            this.password = password;
        }

        String schema() {
            return schema;
        }

        static OwnedSchema prepare() {
            String password = System.getenv("NEXION_TEST_DB_PASSWORD");
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("NEXION_TEST_DB_PASSWORD_REQUIRED_FOR_M3_WS_IT");
            }
            String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
            if (!schema.matches("nexion_m3_ws_it_[0-9a-f]{32}")) {
                throw new IllegalStateException("M3_WS_IT_SCHEMA_INVALID");
            }
            OwnedSchema fixture = new OwnedSchema(schema, password);
            fixture.create();
            return fixture;
        }

        String jdbcUrl() {
            return "jdbc:mysql://127.0.0.1:3306/" + schema
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai";
        }

        String password() {
            return password;
        }

        private void create() {
            boolean created = false;
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", password);
                    Statement sql = connection.createStatement()) {
                sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
                created = true;
                sql.execute("USE " + schema);
                try (var result = sql.executeQuery("SELECT DATABASE()")) {
                    if (!result.next() || !schema.equals(result.getString(1))) {
                        throw new IllegalStateException("M3_WS_IT_SCHEMA_OWNERSHIP_UNVERIFIED");
                    }
                }
                sql.execute("""
                        CREATE TABLE nx_conversation (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          conversation_no VARCHAR(40) NOT NULL UNIQUE,
                          user_id BIGINT NOT NULL,
                          conversation_type VARCHAR(16) NOT NULL,
                          status VARCHAR(16) NOT NULL,
                          owner_agent_id VARCHAR(40) NULL,
                          owner_agent_name VARCHAR(100) NULL,
                          unread_count INT NOT NULL DEFAULT 0,
                          last_message VARCHAR(2000) NULL,
                          last_message_at DATETIME NULL,
                          version BIGINT NOT NULL DEFAULT 0,
                          is_deleted INT NOT NULL DEFAULT 0,
                          created_at DATETIME NOT NULL,
                          updated_at DATETIME NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                sql.execute("""
                        CREATE TABLE nx_conversation_message (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          conversation_id BIGINT NOT NULL,
                          conversation_no VARCHAR(40) NOT NULL,
                          sender_id BIGINT NULL,
                          sender_type VARCHAR(16) NOT NULL,
                          sender_name VARCHAR(100) NOT NULL,
                          content VARCHAR(2000) NOT NULL,
                          is_deleted INT NOT NULL DEFAULT 0,
                          created_at DATETIME NOT NULL,
                          updated_at DATETIME NOT NULL,
                          KEY idx_m3_message_conversation (conversation_no,id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                sql.execute("""
                        CREATE TABLE nx_conversation_message_receipt (
                          message_id BIGINT PRIMARY KEY,
                          conversation_no VARCHAR(40) NOT NULL,
                          receipt_status VARCHAR(16) NOT NULL DEFAULT 'sent',
                          read_by VARCHAR(64) NULL,
                          read_at DATETIME NULL,
                          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                          KEY idx_m3_receipt_conversation (conversation_no,updated_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                sql.execute("""
                        CREATE TABLE nx_conversation_transfer (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          conversation_no VARCHAR(40) NOT NULL,
                          from_agent_id VARCHAR(40) NULL,
                          from_agent_name VARCHAR(100) NULL,
                          to_type VARCHAR(20) NULL,
                          to_id VARCHAR(40) NULL,
                          to_name VARCHAR(100) NULL,
                          reason VARCHAR(200) NULL,
                          status VARCHAR(20) NOT NULL,
                          operator VARCHAR(100) NULL,
                          transferred_at DATETIME NULL,
                          is_deleted INT NOT NULL DEFAULT 0,
                          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                sql.execute("""
                        CREATE TABLE nx_admin_idempotency_record (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          scope VARCHAR(128) NOT NULL,
                          idempotency_key VARCHAR(128) NOT NULL,
                          request_hash VARCHAR(64) NOT NULL,
                          status VARCHAR(16) NOT NULL,
                          response_json JSON NULL,
                          error_message VARCHAR(500) NULL,
                          expires_at DATETIME NOT NULL,
                          is_deleted INT NOT NULL DEFAULT 0,
                          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                          UNIQUE KEY uk_m3_idempotency (scope,idempotency_key),
                          KEY idx_admin_idem_expiry_claim (status,is_deleted,expires_at,id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
            } catch (Exception ex) {
                if (created) {
                    try {
                        fixtureDrop(schema, password);
                    } catch (Exception drop) {
                        ex.addSuppressed(drop);
                    }
                }
                throw new IllegalStateException("M3_WS_IT_SCHEMA_CREATE_FAILED", ex);
            }
        }

        @Override
        public void close() throws Exception {
            if (!closed) {
                fixtureDrop(schema, password);
                closed = true;
            }
        }

        private static void fixtureDrop(String schema, String password) throws Exception {
            if (!schema.matches("nexion_m3_ws_it_[0-9a-f]{32}")) {
                throw new IllegalStateException("M3_WS_IT_SCHEMA_DROP_REFUSED");
            }
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", password);
                    Statement sql = connection.createStatement()) {
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }
}
