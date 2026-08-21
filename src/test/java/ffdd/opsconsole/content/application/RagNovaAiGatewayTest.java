package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RagNovaAiGatewayTest {
    private static final String MODEL = "gemma4-e4b-ctx32k:latest";
    private static final String COLLECTION = "customer_support_knowledge_prd_v2_20260814";
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
                     "collection":"customer_support_knowledge_prd_v2_20260814",
                     "model":"gemma4-e4b-ctx32k:latest","context_count":2}
                    """);
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String answer = new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL,
                "zh",
                "app-user-42",
                List.of(
                        new NovaAiGateway.Message("user", "旧问题"),
                        new NovaAiGateway.Message("assistant", "旧回答"),
                        new NovaAiGateway.Message("user", "NexGrid 和 Nexion 是什么关系？")),
                128));

        assertThat(answer).isEqualTo("Nexion 已更名为 NexGrid。");
        assertThat(captured.get())
                .containsEntry("question", "NexGrid 和 Nexion 是什么关系？")
                .containsEntry("response_language", "zh")
                .containsEntry("user_id", "app-user-42")
                .containsEntry("collection", COLLECTION)
                .containsEntry("max_output_tokens", 128)
                .containsEntry("show_citations", false)
                .containsEntry("use_llm", true);
        assertThat((List<?>) captured.get().get("messages")).isEmpty();
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
                     "collection":"customer_support_knowledge_prd_v2_20260814",
                     "model":"gemma4-e4b-ctx32k:latest","context_count":1}
                    """);
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String answer = new RagNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                MODEL,
                "en",
                "app-user-42",
                List.of(new NovaAiGateway.Message("user", "What is NexGrid?")),
                1_024));

        assertThat(answer).isEqualTo("Nexion was renamed to NexGrid.");
        assertThat(upgradeHeader.get()).isNull();
    }

    @Test
    void availabilityRequiresTheConfiguredModelAndQdrantCollection() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, """
                {"status":"ok","collection":"customer_support_knowledge_prd_v2_20260814",
                 "llm_model":"gemma4-e4b-ctx32k:latest",
                 "checks":{"qdrant":"ok","ollama":"ok","model":"ok"}}
                """));
        server.start();

        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isTrue();
        properties.setRagCollection("wrong_collection");
        assertThat(new RagNovaAiGateway(properties, objectMapper).available()).isFalse();
    }

    @Test
    void rejectsAChatResponseFromTheWrongCollection() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> respond(exchange, 200, """
                {"answer":"wrong corpus","sources":[],"need_human":false,
                 "collection":"stale_collection","model":"gemma4-e4b-ctx32k:latest","context_count":0}
                """));
        server.start();
        NovaAiProperties properties = properties();
        properties.setRagBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        RagNovaAiGateway gateway = new RagNovaAiGateway(properties, objectMapper);
        NovaAiGateway.ChatRequest request = new NovaAiGateway.ChatRequest(
                MODEL, "en", "app-user-42", List.of(new NovaAiGateway.Message("user", "NexGrid?")), 64);

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
                MODEL, "en", "app-user-42", List.of(new NovaAiGateway.Message("user", "must not be sent")), 64);

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
