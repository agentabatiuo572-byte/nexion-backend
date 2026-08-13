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

class OllamaNovaAiGatewayTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsNonStreamingNonThinkingChatAndReturnsOnlyAssistantContent() throws Exception {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            captured.set(objectMapper.readValue(exchange.getRequestBody(), new TypeReference<>() { }));
            respond(exchange, 200, """
                    {"model":"gemma4-e4b-ctx32k:latest","message":{"role":"assistant","content":"local answer"},"done":true,"thinking":"must not escape"}
                    """);
        });
        server.start();

        NovaAiProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String answer = new OllamaNovaAiGateway(properties, objectMapper).chat(new NovaAiGateway.ChatRequest(
                properties.getModel(),
                List.of(new NovaAiGateway.Message("system", "safe"), new NovaAiGateway.Message("user", "hello")),
                128));

        assertThat(answer).isEqualTo("local answer");
        assertThat(captured.get()).containsEntry("stream", false).containsEntry("think", false)
                .containsEntry("model", properties.getModel());
    }

    @Test
    void rejectsNonLoopbackTargetsBeforeSendingUserContent() {
        NovaAiProperties properties = properties();
        properties.setBaseUrl("https://example.com/ollama");

        OllamaNovaAiGateway gateway = new OllamaNovaAiGateway(properties, objectMapper);
        NovaAiGateway.ChatRequest request = new NovaAiGateway.ChatRequest(
                properties.getModel(), List.of(new NovaAiGateway.Message("user", "must not be sent")), 64);

        assertThatThrownBy(() -> gateway.chat(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NOVA_AI_LOCAL_BASE_URL_INVALID");
    }

    private NovaAiProperties properties() {
        NovaAiProperties properties = new NovaAiProperties();
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        properties.setModel("gemma4-e4b-ctx32k:latest");
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
