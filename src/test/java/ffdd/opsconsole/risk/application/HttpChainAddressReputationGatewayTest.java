package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpChainAddressReputationGatewayTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void callsTheConfiguredProviderWithoutLeakingOrInventingTheScore() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = start(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-K3-Key")).isEqualTo("test-secret");
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"score\":0.39}");
        });

        var gateway = new HttpChainAddressReputationGateway(properties(server, 500), new ObjectMapper());

        assertThat(gateway.score("USDT-TRC20", "TRAbC123Address"))
                .isEqualByComparingTo("0.39");
        assertThat(requestBody.get()).contains("\"chain\":\"USDT-TRC20\"")
                .contains("\"address\":\"TRAbC123Address\"");
    }

    @Test
    void failsClosedForMissingConfigurationMalformedPayloadAndTimeout() throws Exception {
        K3AddressReputationProperties missing = new K3AddressReputationProperties();
        var missingGateway = new HttpChainAddressReputationGateway(missing, new ObjectMapper());
        assertThatThrownBy(() -> missingGateway.score("USDT-TRC20", "TRAbC123Address"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_ADDRESS_REPUTATION_UNAVAILABLE");

        server = start(exchange -> respond(exchange, 200, "{\"score\":\"0.39\"}"));
        var malformedGateway = new HttpChainAddressReputationGateway(properties(server, 500), new ObjectMapper());
        assertThatThrownBy(() -> malformedGateway.score("USDT-TRC20", "TRAbC123Address"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_ADDRESS_REPUTATION_INVALID");
        server.stop(0);

        server = start(exchange -> {
            try {
                Thread.sleep(150);
                respond(exchange, 200, "{\"score\":0.39}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        var timeoutGateway = new HttpChainAddressReputationGateway(properties(server, 25), new ObjectMapper());
        assertThatThrownBy(() -> timeoutGateway.score("USDT-TRC20", "TRAbC123Address"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_ADDRESS_REPUTATION_UNAVAILABLE");
    }

    private HttpServer start(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/score", exchange -> handler.handle(exchange));
        httpServer.start();
        return httpServer;
    }

    private K3AddressReputationProperties properties(HttpServer httpServer, int readTimeoutMs) {
        K3AddressReputationProperties properties = new K3AddressReputationProperties();
        properties.setEndpoint("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/score");
        properties.setApiKey("test-secret");
        properties.setApiKeyHeader("X-K3-Key");
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutMs(readTimeoutMs);
        return properties;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
