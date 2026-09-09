package ffdd.opsconsole.content.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.shared.api.ApiResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.Field;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Wire-level contract only: a random-port embedded Tomcat and actual WebSocket client,
 * with identity, command and persistence boundaries replaced by mocks.
 */
@ActiveProfiles("dev")
@SpringBootTest(
        classes = ConversationSocketWireTest.WireApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"nexion.cors.development-allowed-origins=http://127.0.0.1:5173", "nexion.test.websocket-wire=true"})
class ConversationSocketWireTest {
    private static final Duration WAIT = Duration.ofSeconds(3);

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    private ConversationSocketTickets tickets;

    @org.springframework.beans.factory.annotation.Autowired
    private ConversationSocketHandler handler;

    @org.springframework.beans.factory.annotation.Autowired
    private ConversationSocketAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    private ConversationSocketCommands commands;

    private final List<WireClient> clients = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(WireClient::close);
        clients.clear();
    }

    @Test
    void onceTicketAckAuthorizationPresenceTypingAndReconnectTravelOverTheRealWire() throws Exception {
        Authentication user = new UsernamePasswordAuthenticationToken("100", null, List.of());
        Authentication otherUser = new UsernamePasswordAuthenticationToken("200", null, List.of());
        Authentication agent = new UsernamePasswordAuthenticationToken("9", null, List.of(
                new SimpleGrantedAuthority("service_m3_read"), new SimpleGrantedAuthority("service_m3_write")));

        when(access.authenticate("token-user", "USER")).thenReturn(user);
        when(access.authenticate("token-other", "USER")).thenReturn(otherUser);
        when(access.authenticate("token-agent", "ADMIN")).thenReturn(agent);
        when(access.canRead(any(Authentication.class), anyString(), eq("CV-1"))).thenAnswer(invocation -> {
            Authentication candidate = invocation.getArgument(0);
            return candidate == user || candidate == agent;
        });
        when(access.participants("CV-1")).thenReturn(Optional.of(new ConversationSocketAccess.Participants("100", "seat-9")));
        when(access.presenceKeys(user, "USER")).thenReturn(Set.of("USER:100"));
        when(access.presenceKeys(otherUser, "USER")).thenReturn(Set.of("USER:200"));
        when(access.presenceKeys(agent, "ADMIN")).thenReturn(Set.of("ADMIN:seat-9"));
        doReturn(ApiResult.ok(Map.of("messageId", 31))).when(commands).execute(eq(user), eq("USER"), any());

        String singleUse = tickets.issue("Bearer token-user", "USER");
        WireClient userClient = connect();
        userClient.send(Map.of("type", "auth", "ticket", singleUse));
        assertEquals("ready", userClient.await(frame -> "ready".equals(frame.path("type").asText())).path("type").asText());

        WireClient replay = connect();
        replay.send(Map.of("type", "auth", "ticket", singleUse));
        assertEquals(401, replay.await(frame -> "error".equals(frame.path("type").asText())).path("code").asInt());

        userClient.send(Map.of(
                "type", "command", "requestId", "reply-1", "operation", "reply", "conversationNo", "CV-1",
                "idempotencyKey", "wire-key-001", "body", Map.of("body", "hello")));
        JsonNode ack = userClient.await(frame -> "ack".equals(frame.path("type").asText())
                && "reply-1".equals(frame.path("requestId").asText()));
        assertEquals(0, ack.path("result").path("code").asInt());
        verify(commands).execute(eq(user), eq("USER"), any());

        WireClient otherClient = connect();
        otherClient.send(Map.of("type", "auth", "ticket", tickets.issue("Bearer token-other", "USER")));
        otherClient.await(frame -> "ready".equals(frame.path("type").asText()));
        handler.changed(ConversationMessageEvent.builder().conversationNo("CV-1").build());
        assertEquals("CV-1", userClient.await(frame -> "event".equals(frame.path("type").asText()))
                .path("conversationNo").asText());
        assertNull(otherClient.poll(frame -> "event".equals(frame.path("type").asText()), Duration.ofMillis(500)));

        WireClient agentClient = connect();
        agentClient.send(Map.of("type", "auth", "ticket", tickets.issue("Bearer token-agent", "ADMIN")));
        agentClient.await(frame -> "ready".equals(frame.path("type").asText()));
        userClient.send(Map.of("type", "watch", "conversationNo", "CV-1"));
        agentClient.send(Map.of("type", "watch", "conversationNo", "CV-1"));
        assertTrue(userClient.await(frame -> "presence".equals(frame.path("type").asText())
                && frame.path("online").asBoolean()).path("online").asBoolean());

        agentClient.send(Map.of("type", "typing", "conversationNo", "CV-1", "active", true));
        assertTrue(userClient.await(frame -> "presence".equals(frame.path("type").asText())
                && frame.path("typing").asBoolean()).path("typing").asBoolean());

        userClient.clearFrames();
        setActiveTypingRemaining(900);
        broadcastPresenceNow();
        JsonNode remainingTyping = userClient.await(frame -> "presence".equals(frame.path("type").asText())
                && frame.path("typing").asBoolean());
        assertTrue(remainingTyping.path("expiresIn").asLong() > 0);
        assertTrue(remainingTyping.path("expiresIn").asLong() <= 900,
                "a refresh must return the original remaining typing lease, never another five seconds");

        userClient.clearFrames();
        setActiveTypingRemaining(0);
        broadcastPresenceNow();
        JsonNode inactiveTyping = userClient.await(frame -> "presence".equals(frame.path("type").asText())
                && !frame.path("typing").asBoolean());
        assertEquals(0, inactiveTyping.path("expiresIn").asLong());

        userClient.clearFrames();
        agentClient.close();
        assertFalse(userClient.await(frame -> "presence".equals(frame.path("type").asText())
                && !frame.path("online").asBoolean()).path("online").asBoolean());

        userClient.clearFrames();
        WireClient reconnectedAgent = connect();
        reconnectedAgent.send(Map.of("type", "auth", "ticket", tickets.issue("Bearer token-agent", "ADMIN")));
        reconnectedAgent.await(frame -> "ready".equals(frame.path("type").asText()));
        reconnectedAgent.send(Map.of("type", "watch", "conversationNo", "CV-1"));
        assertTrue(userClient.await(frame -> "presence".equals(frame.path("type").asText())
                && frame.path("online").asBoolean()).path("online").asBoolean());
    }

    @SuppressWarnings("unchecked")
    private void setActiveTypingRemaining(long remainingMillis) throws Exception {
        Field clientsField = ConversationSocketHandler.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        Map<String, Object> activeClients = (Map<String, Object>) clientsField.get(handler);
        Object typingClient = activeClients.values().stream()
                .filter(client -> typingUntil(client) > 0)
                .findFirst()
                .orElseThrow();
        Field typingUntil = typingClient.getClass().getDeclaredField("typingUntil");
        typingUntil.setAccessible(true);
        typingUntil.setLong(typingClient, System.currentTimeMillis() + remainingMillis);
    }

    private long typingUntil(Object client) {
        try {
            Field value = client.getClass().getDeclaredField("typingUntil");
            value.setAccessible(true);
            return value.getLong(client);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void broadcastPresenceNow() throws Exception {
        var method = ConversationSocketHandler.class.getDeclaredMethod("broadcastPresence");
        method.setAccessible(true);
        method.invoke(handler);
    }

    private WireClient connect() throws Exception {
        WireClient client = new WireClient(json);
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://127.0.0.1:5173");
        client.session = new StandardWebSocketClient()
                .execute(client, headers, URI.create("ws://127.0.0.1:" + port + "/ws/conversations"))
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        clients.add(client);
        return client;
    }

    private final class WireClient extends TextWebSocketHandler {
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final ObjectMapper mapper;
        private volatile WebSocketSession session;

        private WireClient(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            frames.add(mapper.readTree(message.getPayload()));
        }

        void send(Map<String, ?> frame) throws Exception {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
        }

        JsonNode await(Predicate<JsonNode> expected) throws InterruptedException {
            JsonNode value = poll(expected, WAIT);
            assertNotNull(value, "timed out waiting for expected WebSocket frame; received=" + frames);
            return value;
        }

        JsonNode poll(Predicate<JsonNode> expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (JsonNode frame : frames) if (expected.test(frame)) return frame;
                Thread.sleep(10);
            }
            return null;
        }

        void clearFrames() {
            frames.clear();
        }

        void close() {
            try {
                if (session != null && session.isOpen()) session.close();
            } catch (Exception ignored) {
                // Test cleanup: the embedded server may have already closed a rejected socket.
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "nexion.test.websocket-wire", havingValue = "true")
    @ImportAutoConfiguration({
            ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            WebSocketServletAutoConfiguration.class
    })
    @Import(ConversationSocketConfig.class)
    static class WireApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ConversationSocketAccess conversationSocketAccess() {
            return mock(ConversationSocketAccess.class);
        }

        @Bean
        ConversationSocketCommands conversationSocketCommands() {
            return mock(ConversationSocketCommands.class);
        }

        @Bean
        ConversationSocketTickets conversationSocketTickets(ConversationSocketAccess access) {
            return new ConversationSocketTickets(access);
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
}
