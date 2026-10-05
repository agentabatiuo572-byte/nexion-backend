package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpHdPayGatewayTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void retainsOnlyPlainNonPrivateReasonsForExplicitBusinessRejection() throws Exception {
        AtomicReference<byte[]> response = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/order/api/payOrder/publicCreatePayOrder", exchange -> {
            byte[] bytes = response.get();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            HdPayProperties config = properties(server.getAddress().getPort());
            HttpHdPayGateway gateway = new HttpHdPayGateway(config, objectMapper);
            record Case(Object msg, String expected) {}
            for (Case test : List.of(
                    new Case("金额必须为整数", "金额必须为整数"),
                    new Case("充值金额不正确：请输入整数", "充值金额不正确：请输入整数"),
                    new Case("该ip禁止访问", "该ip禁止访问"),
                    new Case("Amount must be between 10000 and 5000000 VND.", "Amount must be between 10000 and 5000000 VND."),
                    new Case("Invalid 1234567890123456789", ""),
                    new Case("Invalid VQR-1", ""),
                    new Case("Invalid 0123456789abcdef0123456789abcdef", ""),
                    new Case("Invalid 203.0.113.9", ""),
                    new Case("sign 0123456789abcdef0123456789abcdef", ""),
                    new Case("account 123456789012", ""),
                    new Case("password shortvalue", ""),
                    new Case("Authorization: Bearer abc123abc123", ""),
                    new Case("Invalid api-key abcd1234abcd1234", ""),
                    new Case("Invalid apiKey abcd1234abcd1234", ""),
                    new Case("Please visit www.private.example.com", ""),
                    new Case("Please visit private.example.com", ""),
                    new Case("https://private.example.com/failure", ""),
                    new Case("<script>alert(1)</script>", ""),
                    new Case("amount\nprivate", ""),
                    new Case("a".repeat(257), ""),
                    new Case(Map.of("private", "hidden"), ""),
                    new Case(500, ""), new Case("", ""))) {
                response.set(objectMapper.writeValueAsBytes(Map.of("code", 500, "msg", test.msg())));
                assertThatThrownBy(() -> gateway.createPayOrder(new HdPayGateway.CreatePayOrder(
                        "VQR-1", new BigDecimal("100000"), "203.0.113.9")))
                        .isInstanceOf(HdPayGatewayException.class).hasMessage("HDPAY_CREATE_EXPLICIT_REJECTED")
                        .satisfies(ex -> {
                            assertThat(((HdPayGatewayException) ex).ambiguous()).isFalse();
                            assertThat(((HdPayGatewayException) ex).providerReason()).isEqualTo(test.expected());
                        });
            }
            response.set(objectMapper.writeValueAsBytes(Map.of("code", "500", "msg", "金额必须为整数")));
            assertThatThrownBy(() -> gateway.createPayOrder(new HdPayGateway.CreatePayOrder(
                    "VQR-1", new BigDecimal("100000"), "203.0.113.9")))
                    .hasMessage("HDPAY_CREATE_REJECTED").satisfies(ex -> {
                        assertThat(((HdPayGatewayException) ex).ambiguous()).isTrue();
                        assertThat(((HdPayGatewayException) ex).providerReason()).isEmpty();
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesTheExplicitProviderProxyWithoutChangingOtherJvmNetworking() {
        HdPayProperties properties = new HdPayProperties();
        properties.setProxyHost("127.0.0.1");
        properties.setProxyPort(7890);

        HttpClient client = HttpHdPayGateway.buildHttpClient(properties);

        Proxy proxy = client.proxy().orElseThrow()
                .select(URI.create("https://api.hdpayadmin.com"))
                .get(0);
        assertThat(proxy.address()).isEqualTo(new InetSocketAddress("127.0.0.1", 7890));
    }

    @Test
    void leavesTheProviderClientDirectWhenNoProxyIsConfigured() {
        assertThat(HttpHdPayGateway.buildHttpClient(new HdPayProperties()).proxy()).isEmpty();
    }

    @Test
    void createsBankQrOrderUsingTheDocumentedRequestAndReturnsHostedPage() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/order/api/payOrder/publicCreatePayOrder", exchange -> {
            body.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = ("{\"code\":200,\"msg\":\"\",\"data\":"
                    + "\"https://api.hdpayadmin.com/placeAnOrder?orderId=1\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HdPayProperties properties = properties(server.getAddress().getPort());
            HttpHdPayGateway gateway = new HttpHdPayGateway(
                    properties,
                    objectMapper,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());

            HdPayGateway.PayPage page = gateway.createPayOrder(
                    new HdPayGateway.CreatePayOrder("VQR-1", new BigDecimal("100000"), "203.0.113.9"));

            assertThat(page.url()).isEqualTo("https://api.hdpayadmin.com/placeAnOrder?orderId=1");
            assertThat(body.get().path("merchantId").asText()).isEqualTo("1234567890123456789");
            assertThat(body.get().path("merchantOrderId").asText()).isEqualTo("VQR-1");
            assertThat(body.get().path("transAmt").decimalValue()).isEqualByComparingTo("100000");
            assertThat(body.get().path("payType").asText()).isEqualTo("BANKQR");
            assertThat(body.get().path("countryCode").asText()).isEqualTo("VN");
            assertThat(body.get().path("ip").asText()).isEqualTo("203.0.113.9");
            assertThat(body.get().path("callbackUrl").asText()).isEqualTo(
                    "https://payments.example.com/openapi/v1/payments/hdpay/pay-in/callback");
            assertThat(body.get().path("sign").asText()).matches("[0-9a-f]{32}");
            assertThat(body.get().has("orderRemark")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void queriesAnOrderUsingTheDocumentedSignedIdentity() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/order/api/payOrder/queryPayOrder", exchange -> {
            body.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"code":200,"msg":"","data":{"orderId":"P-1",
                     "merchantOrderId":"VQR-1","merchantId":"1234567890123456789",
                     "orderStatus":1,"transAmt":100000,"payType":"BANKQR",
                     "appLink":"https://api.hdpayadmin.com/placeAnOrder?orderId=P-1"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HdPayGateway.PayOrder result = new HttpHdPayGateway(
                    properties(server.getAddress().getPort()),
                    objectMapper,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build())
                    .queryPayOrder("VQR-1");

            assertThat(result.providerOrderId()).isEqualTo("P-1");
            assertThat(result.transAmt()).isEqualByComparingTo("100000");
            assertThat(result.appLink()).contains("orderId=P-1");
            assertThat(body.get().path("merchantId").asText()).isEqualTo("1234567890123456789");
            assertThat(body.get().path("merchantOrderId").asText()).isEqualTo("VQR-1");
            assertThat(body.get().path("sign").asText()).matches("[0-9a-f]{32}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recordsSafeResponseDiagnosticsWithoutChangingCreateOutcomes() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HttpHdPayGateway.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logs.start();
        logger.addAppender(logs);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> responseBody = new AtomicReference<>();
        AtomicInteger responseStatus = new AtomicInteger(200);
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> signature = new AtomicReference<>();
        server.createContext("/api/order/api/payOrder/publicCreatePayOrder", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            signature.set(request.path("sign").asText());
            requestCount.incrementAndGet();
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HdPayProperties properties = properties(server.getAddress().getPort());
            HttpHdPayGateway gateway = new HttpHdPayGateway(properties, objectMapper,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
            String paymentUrl = "https://api.hdpayadmin.com/placeAnOrder?orderId=private-fixture";
            String untrustedUrl = "https://untrusted.example.com/pay?orderId=untrusted-fixture";
            String unsafeMessage = "echo FAKE HOLDER 0123456789012345 203.0.113.9 "
                    + properties.getMerchantId() + " " + properties.getMd5Key() + "\nunsafe-message";
            String unsafeCode = "provider-code-secret\nunsafe-code";
            record Scenario(int status, String body, String error, boolean ambiguous,
                            String shape, String reason) {}
            List<Scenario> scenarios = List.of(
                    new Scenario(200, objectMapper.writeValueAsString(Map.of(
                            "code", 408, "msg", "该ip禁止访问", "data", Map.of("echo", unsafeMessage))),
                            "HDPAY_CREATE_EXPLICIT_REJECTED", false,
                            "providerCode=408 codeType=NUMBER rootType=OBJECT dataType=OBJECT msgType=STRING", "IP_NOT_ALLOWED"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of(
                            "code", unsafeCode, "msg", unsafeMessage, "data", paymentUrl)),
                            "HDPAY_CREATE_REJECTED", true,
                            "providerCode=INVALID codeType=STRING rootType=OBJECT dataType=STRING msgType=STRING", "UNCONFIRMED_RESPONSE"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of(
                            "code", "408", "msg", "该ip禁止访问", "data", paymentUrl)),
                            "HDPAY_CREATE_REJECTED", true,
                            "providerCode=408 codeType=STRING rootType=OBJECT dataType=STRING msgType=STRING", "UNCONFIRMED_RESPONSE"),
                    new Scenario(503, objectMapper.writeValueAsString(Map.of(
                            "code", 429, "msg", unsafeMessage, "data", paymentUrl)),
                            "HDPAY_HTTP_503", false,
                            "providerCode=429 codeType=NUMBER rootType=OBJECT dataType=STRING msgType=STRING", "HTTP_REJECTED"),
                    new Scenario(503, objectMapper.writeValueAsString(Map.of(
                            "code", 200, "msg", unsafeMessage, "data", paymentUrl)),
                            "HDPAY_HTTP_503", false,
                            "providerCode=200 codeType=NUMBER rootType=OBJECT dataType=STRING msgType=STRING", "HTTP_REJECTED"),
                    new Scenario(503, unsafeMessage, "HDPAY_HTTP_503", false,
                            "providerCode=MISSING codeType=MISSING rootType=INVALID_JSON dataType=MISSING msgType=MISSING", "HTTP_REJECTED"),
                    new Scenario(200, unsafeMessage, "HDPAY_CREATE_IO_ERROR", true,
                            "providerCode=MISSING codeType=MISSING rootType=INVALID_JSON dataType=MISSING msgType=MISSING", "INVALID_JSON"),
                    new Scenario(200, "[408]", "HDPAY_CREATE_REJECTED", true,
                            "providerCode=MISSING codeType=MISSING rootType=ARRAY dataType=MISSING msgType=MISSING", "UNCONFIRMED_RESPONSE"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of("msg", unsafeMessage, "data", paymentUrl)),
                            "HDPAY_CREATE_REJECTED", true,
                            "providerCode=MISSING codeType=MISSING rootType=OBJECT dataType=STRING msgType=STRING", "UNCONFIRMED_RESPONSE"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of("code", 200.7, "msg", unsafeMessage, "data", paymentUrl)),
                            "HDPAY_CREATE_REJECTED", true,
                            "providerCode=INVALID codeType=NUMBER rootType=OBJECT dataType=STRING msgType=STRING", "UNCONFIRMED_RESPONSE"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of(
                            "code", 200, "msg", unsafeMessage, "data", Map.of("url", paymentUrl))),
                            "HDPAY_PAYMENT_PAGE_UNTRUSTED", true,
                            "providerCode=200 codeType=NUMBER rootType=OBJECT dataType=OBJECT msgType=STRING", "UNCONFIRMED_RESPONSE"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of(
                            "code", 200, "msg", unsafeMessage, "data", untrustedUrl)),
                            "HDPAY_PAYMENT_PAGE_UNTRUSTED", true,
                            "providerCode=200 codeType=NUMBER rootType=OBJECT dataType=STRING msgType=STRING", "UNCONFIRMED_RESPONSE"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of(
                            "code", 200, "msg", unsafeMessage, "data", paymentUrl)), null, false,
                            "providerCode=200 codeType=NUMBER rootType=OBJECT dataType=STRING msgType=STRING", "ACCEPTED"),
                    new Scenario(200, objectMapper.writeValueAsString(Map.of(
                            "code", "200", "msg", unsafeMessage, "data", paymentUrl)), null, false,
                            "providerCode=200 codeType=STRING rootType=OBJECT dataType=STRING msgType=STRING", "ACCEPTED"));
            int index = 0;
            for (Scenario scenario : scenarios) {
                responseStatus.set(scenario.status());
                responseBody.set(scenario.body());
                HdPayGateway.CreatePayOrder order = new HdPayGateway.CreatePayOrder(
                        "VQR-1", new BigDecimal("100000"), "203.0.113.9");
                if (scenario.error() == null) {
                    assertThat(gateway.createPayOrder(order).url()).isEqualTo(paymentUrl);
                } else {
                    assertThatThrownBy(() -> gateway.createPayOrder(order))
                            .isInstanceOf(HdPayGatewayException.class)
                            .hasMessage(scenario.error())
                            .satisfies(ex -> assertThat(((HdPayGatewayException) ex).ambiguous())
                                    .isEqualTo(scenario.ambiguous()));
                }
                assertThat(requestCount.get()).isEqualTo(index + 1);
                assertThat(logs.list).hasSize(index + 1);
                String output = logs.list.get(index).getFormattedMessage();
                String orderHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest("VQR-1".getBytes(StandardCharsets.UTF_8)));
                String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(scenario.body().getBytes(StandardCharsets.UTF_8)));
                assertThat(output).contains("orderRef=sha256:" + orderHash.substring(0, 16),
                        "httpStatus=" + scenario.status(), scenario.shape(),
                        "bodySha256=" + bodyHash, "reason=" + scenario.reason());
                assertThat(output).doesNotContain("VQR-1", unsafeCode, unsafeMessage, "unsafe-code", "unsafe-message",
                        "FAKE HOLDER", "0123456789012345", "203.0.113.9", properties.getMerchantId(),
                        properties.getMd5Key(), signature.get(), paymentUrl, "private-fixture", untrustedUrl, "untrusted-fixture");
                index++;
            }
        } finally {
            server.stop(0);
            logger.detachAppender(logs);
            logger.setLevel(previousLevel);
            logs.stop();
        }
    }

    private HdPayProperties properties(int port) {
        HdPayProperties properties = new HdPayProperties();
        properties.setMode(HdPayProperties.Mode.PROVIDER);
        properties.setBaseUrl("http://127.0.0.1:" + port + "/api/order");
        properties.setCallbackBaseUrl("https://payments.example.com");
        properties.setCallbackHosts(java.util.List.of("payments.example.com"));
        properties.setMerchantId("1234567890123456789");
        properties.setMd5Key("0123456789abcdef0123456789abcdef");
        properties.setPayType("BANKQR");
        properties.setCountryCode("VN");
        properties.setPaymentPageHosts(java.util.List.of("api.hdpayadmin.com"));
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);
        properties.setAllowInsecureBaseUrlForTests(true);
        return properties;
    }
}
