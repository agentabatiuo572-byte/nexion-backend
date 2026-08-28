package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RagNovaAiGatewayTest {
    private static final String MODEL = "gemma4-e4b-ctx32k:latest";
    private static final String COLLECTION = "customer_support_knowledge_prd_v2_20260814";
    private static final String RAG_SESSION_ID = "nova-v1-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void defaultOutputBudgetLeavesRoomForGemmaReasoningAndAnswer() {
        assertThat(new NovaAiProperties().getMaxOutputTokens()).isEqualTo(1_024);
    }

    @Test
    void sendsTheCurrentQuestionLanguageAndServerSessionWithoutClientHistory() throws Exception {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            captured.set(objectMapper.readValue(exchange.getRequestBody(), new TypeReference<>() { }));
            respond(exchange, 200, """
                    {"answer":"Nexion 已更名为 NexGrid。","sources":[],"need_human":false,
                     "model":"generated-answer"}
                    """);
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String answer = new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL,
                "zh",
                RAG_SESSION_ID,
                List.of(
                        new NovaAiGateway.Message("user", "旧问题"),
                        new NovaAiGateway.Message("assistant", "旧回答"),
                        new NovaAiGateway.Message("user", "NexGrid 和 Nexion 是什么关系？")),
                128));

        assertThat(answer).isEqualTo("Nexion 已更名为 NexGrid。");
        assertThat(captured.get())
                .containsEntry("question", "NexGrid 和 Nexion 是什么关系？")
                .containsEntry("response_language", "zh")
                .containsEntry("session_id", RAG_SESSION_ID);
        assertThat(captured.get()).doesNotContainKeys(
                "messages", "user_id", "collection", "top_k", "min_score",
                "use_llm", "show_citations", "max_output_tokens");
    }

    @Test
    void usesHttp11SoUvicornReceivesThePostBodyInsteadOfAnH2cUpgradeProbe() throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            if (upgradeHeader.get() != null) {
                respond(exchange, 422, """
                        {"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}
                        """);
                return;
            }
            respond(exchange, 200, """
                    {"answer":"Nexion was renamed to NexGrid.","sources":[],"need_human":false,
                     "model":"guardrail-current-fact"}
                    """);
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String answer = new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL,
                "en",
                RAG_SESSION_ID,
                List.of(new NovaAiGateway.Message("user", "What is NexGrid?")),
                1_024));

        assertThat(answer).isEqualTo("Nexion was renamed to NexGrid.");
        assertThat(upgradeHeader.get()).isNull();
    }

    @Test
    void availabilityUsesOnlyThePublicHealthStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isTrue();
        properties.setRagCollection("wrong_collection");
        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isTrue();
    }

    @Test
    void acceptsTheBracketedIpv6LoopbackUsedByTheControlledRuntime() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://[::1]:" + server.getAddress().getPort());

        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isTrue();
    }

    @Test
    void controlledStartupScriptUsesTheSameIpv6LoopbackEndpoint() throws Exception {
        String script = Files.readString(Path.of("scripts", "start_ops_console_monolith.ps1"));

        assertThat(script)
                .contains("NEXION_NOVA_AI_RAG_BASE_URL=http://[::1]:8010")
                .doesNotContain("NEXION_NOVA_AI_RAG_BASE_URL=http://127.0.0.1:8010");
    }

    @Test
    void rejectsAChatResponseThatExposesAnUnknownModelIdentifier() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> respond(exchange, 200, """
                {"answer":"wrong corpus","sources":[],"need_human":false,
                 "model":"unexpected-internal-model"}
                """));
        server.start();
        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        RagNovaAiGateway gateway = new RagNovaAiGateway(properties, objectMapper);
        NovaAiGateway.ChatRequest request = new NovaAiGateway.ChatRequest(
                MODEL, "en", RAG_SESSION_ID, List.of(new NovaAiGateway.Message("user", "NexGrid?")), 64);

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_RESPONSE_INVALID");
    }

    @Test
    void rejectsNonLoopbackRagTargetsBeforeSendingUserContent() {
        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("https://example.com/rag");
        RagNovaAiGateway gateway = new RagNovaAiGateway(properties, objectMapper);
        NovaAiGateway.ChatRequest request = new NovaAiGateway.ChatRequest(
                MODEL, "en", RAG_SESSION_ID, List.of(new NovaAiGateway.Message("user", "must not be sent")), 64);

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_UNAVAILABLE");
    }

    private NovaAiProperties properties() {
        NovaAiProperties properties = new NovaAiProperties();
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        properties.setModel(MODEL);
        properties.setRagCollection(COLLECTION);
        properties.setConnectTimeoutMs(1_000);
        properties.setReadTimeoutMs(3_000);
        return properties;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
