package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpHdPayGatewayTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

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
