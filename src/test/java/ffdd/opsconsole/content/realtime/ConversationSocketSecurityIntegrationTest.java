package ffdd.opsconsole.content.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import ffdd.opsconsole.content.terms.LegalTermsService;
import ffdd.opsconsole.content.web.AppSupportController;
import ffdd.opsconsole.content.web.OpsConversationController;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Security-chain integration: this deliberately uses actual SecurityConfig,
 * JwtAuthenticationFilter, JWT signing/parsing, ticket controller, access,
 * tickets, handler and commands. Only persistence/ops collaborators are mocks.
 */
@ActiveProfiles("dev")
@SpringBootTest(classes = ConversationSocketSecurityIntegrationTest.SecurityApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "nexion.jwt.secret=socket-security-integration-test-secret-0123456789",
                "nexion.cors.development-allowed-origins=http://127.0.0.1:5173",
                "nexion.websocket.allowed-origins=http://127.0.0.1:5173"})
class ConversationSocketSecurityIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    @LocalServerPort int port;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired AdminPermissionCache permissions;
    @Autowired ObjectMapper json;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    @BeforeEach void activeReadOnlyAdmin() {
        when(sessions.isSessionActive(7L, "socket-session")).thenReturn(true);
        when(permissions.getPermissionCodes(7L)).thenReturn(Set.of("service_m3_read"));
    }

    @AfterEach void closeClients() {
        clients.forEach(Client::close);
        clients.clear();
        reset(sessions, permissions);
    }

    @Test void readOnlyM3AdminGetsSignedTicketAndReachesReadyButCannotUseOrdinaryPostWriteRoute() throws Exception {
        String ticket = ticketFor(signed("socket-session"));
        Client client = connect();
        client.send(json.writeValueAsString(Map.of("type", "auth", "ticket", ticket)));
        assertEquals("ready", client.awaitType("ready").path("type").asText());

        HttpResponse<String> write = post("/api/admin/content/conversations", signed("socket-session"));
        assertEquals(403, write.statusCode(), "read-only M3 must not inherit generic conversation POST write access");
    }

    @Test void noM3PermissionCannotMintATicket() throws Exception {
        when(permissions.getPermissionCodes(7L)).thenReturn(Set.of());
        assertEquals(403, post("/api/admin/content/conversations/realtime-ticket", signed("socket-session")).statusCode());
    }

    @Test void revokedSessionCannotMintANewTicket() throws Exception {
        when(sessions.isSessionActive(7L, "socket-session")).thenReturn(false);
        assertEquals(401, post("/api/admin/content/conversations/realtime-ticket", signed("socket-session")).statusCode());
    }

    private String signed(String session) {
        return tokens.createToken(7L, "ADMIN", "reader", List.of("stale_claim"), session, Duration.ofMinutes(5));
    }

    private String ticketFor(String token) throws Exception {
        HttpResponse<String> response = post("/api/admin/content/conversations/realtime-ticket", token);
        assertEquals(200, response.statusCode(), response.body());
        String ticket = json.readTree(response.body()).path("data").path("ticket").asText();
        assertFalse(ticket.isBlank(), response.body());
        return ticket;
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Origin", "http://127.0.0.1:5173")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Client connect() throws Exception {
        Client client = new Client();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://127.0.0.1:5173");
        client.session = new StandardWebSocketClient().execute(client, headers,
                URI.create("ws://127.0.0.1:" + port + "/ws/conversations")).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        clients.add(client);
        return client;
    }

    private final class Client extends TextWebSocketHandler {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        volatile WebSocketSession session;
        @Override protected void handleTextMessage(WebSocketSession ignored, TextMessage message) throws Exception { frames.add(json.readTree(message.getPayload())); }
        void send(String value) throws Exception { session.sendMessage(new TextMessage(value)); }
        JsonNode awaitType(String type) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (JsonNode value : frames) if (type.equals(value.path("type").asText())) return value;
                Thread.sleep(10);
            }
            fail("missing frame " + type + ": " + frames);
            return null;
        }
        void close() { try { if (session != null && session.isOpen()) session.close(); } catch (Exception ignored) {} }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, WebSocketServletAutoConfiguration.class, JacksonAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({SecurityConfig.class, ConversationSocketConfig.class, ConversationSocketTicketController.class})
    static class SecurityApplication {
        @Bean JwtProperties jwtProperties() { JwtProperties p = new JwtProperties(); p.setSecret("socket-security-integration-test-secret-0123456789"); p.setTtlMinutes(10); return p; }
        @Bean JwtTokenProvider jwtTokenProvider(JwtProperties p) { return new JwtTokenProvider(p); }
        @Bean AdminSessionRegistry adminSessionRegistry() { return mock(AdminSessionRegistry.class); }
        @Bean AdminPermissionCache adminPermissionCache() { return mock(AdminPermissionCache.class); }
        @Bean AuthSessionMapper authSessionMapper() { return mock(AuthSessionMapper.class); }
        @Bean UserOpsMapper userOpsMapper() { return mock(UserOpsMapper.class); }
        @Bean GatewaySecurityProperties gatewaySecurityProperties() { return new GatewaySecurityProperties(); }
        @Bean ImpersonationSessionVerifier impersonationSessionVerifier() { return mock(ImpersonationSessionVerifier.class); }
        @Bean PlatformConfigFacade platformConfigFacade() { return mock(PlatformConfigFacade.class); }
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider tokens, AuthSessionMapper sessions, UserOpsMapper users,
                org.springframework.core.env.Environment env, GatewaySecurityProperties gateway, AdminSessionRegistry admins,
                AdminPermissionCache permissions, ImpersonationSessionVerifier impersonation, PlatformConfigFacade config) {
            return new JwtAuthenticationFilter(tokens, sessions, users, env, gateway, admins, permissions, impersonation, config);
        }
        @Bean AuditLogService auditLogService() { return mock(AuditLogService.class); }
        @Bean AdminAccountStateMapper adminAccountStateMapper() { return mock(AdminAccountStateMapper.class); }
        @Bean AdminRbacAuthorizationFilter adminRbacAuthorizationFilter(AuditLogService audit, AdminAccountStateMapper accounts) {
            return new AdminRbacAuthorizationFilter(audit, accounts);
        }
        @Bean SupportAcceptanceSandboxMapper supportAcceptanceSandboxMapper() { return mock(SupportAcceptanceSandboxMapper.class); }
        @Bean ProductionSupportPathGuard productionSupportPathGuard(org.springframework.core.env.Environment env, SupportAcceptanceSandboxMapper mapper) {
            return new ProductionSupportPathGuard(env, mapper);
        }
        @Bean UserAccountBlocklistVerifier userAccountBlocklistVerifier() { return id -> false; }

        @Bean LegalTermsService legalTermsService() { return mock(LegalTermsService.class); }
        @Bean UserBusinessWriteGateFilter userBusinessWriteGateFilter(UserOpsMapper users, LegalTermsService legalTerms) {
            return new UserBusinessWriteGateFilter(users, legalTerms);
        }
        @Bean ffdd.opsconsole.content.domain.ConversationRepository conversationRepository() { return mock(ffdd.opsconsole.content.domain.ConversationRepository.class); }
        @Bean ConversationSocketAccess conversationSocketAccess(JwtAuthenticationFilter auth, AdminRbacAuthorizationFilter gate,
                UserAccountBlocklistVerifier blocklist, UserBusinessWriteGateFilter userGate,
                ffdd.opsconsole.content.domain.ConversationRepository conversations, ProductionSupportPathGuard production) {
            return new ConversationSocketAccess(auth, gate, blocklist, userGate, conversations, production);
        }
        @Bean ConversationSocketTickets conversationSocketTickets(ConversationSocketAccess access) { return new ConversationSocketTickets(access); }
        @Bean AppSupportController appSupportController() { return mock(AppSupportController.class); }
        @Bean OpsConversationController opsConversationController() { return mock(OpsConversationController.class); }
        @Bean ConversationAdminReadController conversationAdminReadController() { return mock(ConversationAdminReadController.class); }
        @Bean ConversationSocketCommands conversationSocketCommands(AppSupportController app, OpsConversationController admin,
                ConversationAdminReadController reads, ConversationSocketAccess access, ObjectMapper json) {
            return new ConversationSocketCommands(app, admin, reads, access, json);
        }
        @Bean ConversationSocketHandler conversationSocketHandler(ObjectMapper json, ConversationSocketTickets tickets,
                ConversationSocketAccess access, ConversationSocketCommands commands) {
            return new ConversationSocketHandler(json, tickets, access, commands);
        }
    }
}
