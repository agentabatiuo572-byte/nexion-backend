package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpCregisGatewayTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsSignedOfficialFieldsAndStrictlyParsesProjectCoins() throws Exception {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server = start("/api/v1/coins", exchange -> {
            Map<String, Object> body = objectMapper.readValue(exchange.getRequestBody(), new TypeReference<>() { });
            captured.set(body);
            assertThat(new CregisSigner().verify(KEY, body, String.valueOf(body.get("sign")))).isTrue();
            respond(exchange, 200, """
                    {"code":"00000","msg":"ok","data":{
                      "payout_coins":[{"coin_name":"USDT-BEP20","chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955"},
                                      {"coin_name":"BNB","chain_id":"2510","token_id":0}],
                      "address_coins":[{"coin_name":"USDT-BEP20","chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955"},
                                       {"coin_name":"BNB","chain_id":"2510","token_id":0}]}}
                    """);
        });

        assertThat(gateway(500).projectCoins()).extracting(CregisGateway.Coin::currency)
                .containsExactlyInAnyOrder(CregisConstants.USDT_BEP20_CURRENCY, "2510@0");
        assertThat(captured.get()).containsEntry("pid", 42).containsEntry("nonce", "abc123");
        assertThat(String.valueOf(captured.get().get("sign"))).matches("[0-9a-f]{32}");
    }

    @Test
    void neverRetriesAnAmbiguousPayoutSubmissionAndDoesNotLeakTheKey() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = start("/api/v1/payout", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 500, "upstream failed");
        });

        var request = new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x1111111111111111111111111111111111111111",
                new BigDecimal("1.25"), "withdrawal-unknown",
                "https://example.invalid/provider/cregis/payout", "test");

        assertThatThrownBy(() -> gateway(500).createPayout(request))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_SUBMISSION_UNKNOWN")
                .hasMessageNotContaining(KEY);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void rejectsMalformedHttp200AndTimesOutReadOperationsFailClosed() throws Exception {
        server = start("/api/v1/coins", exchange -> respond(exchange, 200, "{\"code\":\"00000\",\"data\":{}}"));
        assertThatThrownBy(() -> gateway(500).projectCoins())
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_RESPONSE_INVALID");
        server.stop(0);

        server = start("/api/v1/coins", exchange -> {
            try {
                Thread.sleep(150);
                respond(exchange, 200, "{}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThatThrownBy(() -> gateway(25).projectCoins())
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_PROVIDER_UNAVAILABLE");
    }

    @Test
    void missingWriteReceiptIsUnknownAndPlainHttpIsNeverAllowedOutsideLoopbackTests() throws Exception {
        server = start("/api/v1/payout", exchange -> respond(exchange, 200,
                "{\"code\":\"00000\",\"msg\":\"ok\",\"data\":{}}"));
        var request = new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x1111111111111111111111111111111111111111",
                BigDecimal.ONE, "withdrawal-missing-receipt",
                "https://example.invalid/provider/cregis/payout", "test");
        assertThatThrownBy(() -> gateway(500).createPayout(request))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_SUBMISSION_UNKNOWN");

        CregisProperties insecure = properties(500);
        var productionGateway = new HttpCregisGateway(insecure, objectMapper);
        assertThatThrownBy(productionGateway::projectCoins)
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_CONFIGURATION_INVALID");
    }

    @Test
    void duplicateProviderBusinessIdIsUnknownRatherThanSafeToRefundOrResubmit() throws Exception {
        server = start("/api/v1/payout", exchange -> respond(exchange, 200,
                "{\"code\":\"E0009\",\"msg\":\"Duplicate business number\",\"data\":{}}"));
        var request = new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x1111111111111111111111111111111111111111",
                BigDecimal.ONE, "withdrawal-duplicate",
                "https://example.invalid/provider/cregis/payout", "test");

        assertThatThrownBy(() -> gateway(500).createPayout(request))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_DUPLICATE_BUSINESS_ID_UNKNOWN");
    }

    @Test
    void adaptsAddressCreateOwnershipAndLegalityRoutes() throws Exception {
        Map<String, ExchangeHandler> routes = new LinkedHashMap<>();
        routes.put("/api/v1/address/create", exchange -> respond(exchange, 200,
                "{\"code\":\"00000\",\"data\":{\"address\":\"0x1111111111111111111111111111111111111111\"}}"));
        routes.put("/api/v1/address/inner", exchange -> respond(exchange, 200,
                "{\"code\":\"00000\",\"data\":{\"result\":true}}"));
        routes.put("/api/v1/address/legal", exchange -> respond(exchange, 200,
                "{\"code\":\"00000\",\"data\":{\"result\":true}}"));
        server = start(routes);

        CregisGateway.Address address = gateway(500).createAddress(
                CregisConstants.BSC_CHAIN_ID, "user-42",
                "https://example.invalid/provider/cregis/deposit", "local-correlation-only");
        assertThat(address.address()).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(gateway(500).addressBelongs(CregisConstants.BSC_CHAIN_ID, address.address())).isTrue();
        assertThat(gateway(500).addressLegal(CregisConstants.BSC_CHAIN_ID, address.address())).isTrue();
    }

    @Test
    void poolRequiresExactProjectAddressAndThreeZeroProviderBalances() throws Exception {
        AtomicReference<String> response = new AtomicReference<>("""
                {"code":"00000","data":{"total":1,"rows":[{"pid":42,
                 "currency":"2510@0x55d398326f99059ff775485246999027b3197955",
                 "address":"0x1111111111111111111111111111111111111111",
                 "total":"0","available":"0","processing":"0"}]}}
                """);
        server = start("/api/v1/sub_address_balance", exchange -> respond(exchange, 200, response.get()));
        String address = "0x1111111111111111111111111111111111111111";
        assertThat(gateway(500).zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY, address)).isTrue();
        response.set(response.get().replace("\"processing\":\"0\"", "\"processing\":\"0.1\""));
        assertThat(gateway(500).zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY, address)).isFalse();
        response.set(response.get().replace("\"pid\":42", "\"pid\":43"));
        assertThatThrownBy(() -> gateway(500).zeroAddressBalance(
                CregisConstants.USDT_BEP20_CURRENCY, address)).hasMessage("CREGIS_RESPONSE_INVALID");
    }

    @Test
    void queryBindsProjectAssetStatusAndSuccessfulTransactionHash() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(queryResponse(
                "42", "6", "0x" + "a".repeat(64)));
        server = start("/api/v1/payout/query", exchange -> respond(exchange, 200, response.get()));

        CregisGateway.PayoutQuery expected = query(9001);
        assertThat(gateway(500).queryPayout(expected)).satisfies(order -> {
            assertThat(order.status()).isEqualTo(CregisGateway.PayoutStatus.SUCCEEDED);
            assertThat(order.txid()).isEqualTo("0x" + "a".repeat(64));
            assertThat(order.thirdPartyId()).isEqualTo("withdrawal-query");
        });

        response.set(queryResponse("42", "4294967302", "0x" + "a".repeat(64)));
        assertThatThrownBy(() -> gateway(500).queryPayout(expected))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_RESPONSE_INVALID");

        response.set(queryResponse("41", "6", "0x" + "a".repeat(64)));
        assertThatThrownBy(() -> gateway(500).queryPayout(expected))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_RESPONSE_INVALID");

        response.set(queryResponse("42", "6", "not-a-bsc-transaction"));
        assertThatThrownBy(() -> gateway(500).queryPayout(expected))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_RESPONSE_INVALID");

        response.set(queryResponse("42", "7", "not-a-bsc-transaction"));
        assertThatThrownBy(() -> gateway(500).queryPayout(expected))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_RESPONSE_INVALID");
    }

    @Test
    void fractionalWriteReceiptAndOversizedReadResponseFailClosed() throws Exception {
        server = start("/api/v1/payout", exchange -> respond(exchange, 200,
                "{\"code\":\"00000\",\"data\":{\"cid\":1.5}}"));
        var request = new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x1111111111111111111111111111111111111111",
                BigDecimal.ONE, "withdrawal-fractional-cid",
                "https://example.invalid/provider/cregis/payout", "test");
        assertThatThrownBy(() -> gateway(500).createPayout(request))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_SUBMISSION_UNKNOWN");
        server.stop(0);

        server = start("/api/v1/coins", exchange -> respond(exchange, 200, "x".repeat(1_048_577)));
        assertThatThrownBy(() -> gateway(500).projectCoins())
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_RESPONSE_INVALID");
    }

    @Test
    void rejectsCallbacksOutsideTheConfiguredBackendOriginAndPath() throws Exception {
        server = start("/api/v1/address/create", exchange -> respond(exchange, 200,
                "{\"code\":\"00000\",\"data\":{\"address\":\"0x1111111111111111111111111111111111111111\"}}"));

        assertThatThrownBy(() -> gateway(500).createAddress(
                CregisConstants.BSC_CHAIN_ID, "user-42",
                "https://attacker.invalid/provider/cregis/deposit", "correlation"))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_REQUEST_INVALID");

        assertThatThrownBy(() -> gateway(500).createAddress(
                CregisConstants.BSC_CHAIN_ID, "user-42",
                "https://example.invalid/provider/cregis/%2e%2e/admin", "correlation"))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_REQUEST_INVALID");

        CregisProperties missingCallbackConfig = properties(500);
        missingCallbackConfig.setCallbackBaseUrl("");
        var missingConfigGateway = new HttpCregisGateway(
                missingCallbackConfig, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
                () -> "abc123", true);
        assertThatThrownBy(() -> missingConfigGateway.createAddress(
                CregisConstants.BSC_CHAIN_ID, "user-42",
                "https://example.invalid/provider/cregis/deposit", "correlation"))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_CONFIGURATION_INVALID");
    }

    @Test
    void rejectsExponentAmountsBeforeSerializationOrNetworkSubmission() throws Exception {
        server = start("/api/v1/payout", exchange -> respond(exchange, 500, "must not be called"));
        var request = new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x1111111111111111111111111111111111111111",
                new BigDecimal("1e2147483647"), "withdrawal-extreme",
                "https://example.invalid/provider/cregis/payout", "test");

        assertThatThrownBy(() -> gateway(500).createPayout(request))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_REQUEST_INVALID");
    }

    @Test
    void responseBodyReadDeadlineStillAppliesAfterHeadersArrive() throws Exception {
        server = start("/api/v1/coins", exchange -> {
            byte[] prefix = "{\"code\":\"00000\",\"data\":{".getBytes(StandardCharsets.UTF_8);
            byte[] suffix = "}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, prefix.length + suffix.length);
            exchange.getResponseBody().write(prefix);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(200);
                exchange.getResponseBody().write(suffix);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway(40).projectCoins())
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_PROVIDER_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void depositTradeQueryRejectsMismatchedProjectOrTransaction() throws Exception {
        String txid = "0x" + "a".repeat(64);
        server = start("/api/v1/trade/page", exchange -> respond(exchange, 200,
                "{\"code\":\"00000\",\"data\":{\"total\":1,\"rows\":[{"
                + "\"pid\":42,\"cid\":123,\"chain_id\":\"2510\",\"token_id\":\""
                + CregisConstants.USDT_BEP20_TOKEN_ID
                + "\",\"to_address\":\"0x1111111111111111111111111111111111111111\","
                + "\"amount\":\"1.25\",\"status\":1,\"txid\":\"" + txid + "\"}]}}"));
        assertThat(gateway(500).depositsByTxid(txid)).singleElement()
                .extracting(CregisGateway.DepositTrade::cid).isEqualTo(123L);
        assertThatThrownBy(() -> gateway(500).depositsByTxid("0x" + "b".repeat(64)))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_RESPONSE_INVALID");
    }

    private HttpCregisGateway gateway(int readTimeoutMs) {
        CregisProperties properties = properties(readTimeoutMs);
        return new HttpCregisGateway(properties, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
                () -> "abc123", true);
    }

    private CregisProperties properties(int readTimeoutMs) {
        CregisProperties properties = new CregisProperties();
        properties.setMode(CregisProperties.Mode.PROVIDER);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCallbackBaseUrl("https://example.invalid/provider/cregis");
        properties.setProjectId(42L);
        properties.setApiKey(KEY);
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutMs(readTimeoutMs);
        return properties;
    }

    private HttpServer start(String path, ExchangeHandler handler) throws IOException {
        return start(Map.of(path, handler));
    }

    private HttpServer start(Map<String, ExchangeHandler> routes) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routes.forEach((path, handler) -> httpServer.createContext(path, handler::handle));
        httpServer.start();
        return httpServer;
    }

    private String queryResponse(String pid, String status, String txid) {
        return "{\"code\":\"00000\",\"data\":{"
                + "\"pid\":" + pid + ","
                + "\"chain_id\":\"2510\","
                + "\"token_id\":\"" + CregisConstants.USDT_BEP20_TOKEN_ID + "\","
                + "\"currency\":\"USDT-BEP20\","
                + "\"address\":\"0x1111111111111111111111111111111111111111\","
                + "\"amount\":\"1.25\","
                + "\"status\":" + status + ","
                + "\"third_party_id\":\"withdrawal-query\","
                + "\"txid\":\"" + txid + "\"}}";
    }

    private CregisGateway.PayoutQuery query(long cid) {
        return new CregisGateway.PayoutQuery(
                cid, "withdrawal-query", "0x1111111111111111111111111111111111111111",
                new BigDecimal("1.25"));
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
