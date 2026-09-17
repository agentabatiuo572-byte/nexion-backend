package ffdd.opsconsole.finance.hdpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HdPayPayoutContractTest {
    final ObjectMapper json = new ObjectMapper();

    @Test void responseBodyMustFinishWithinDeadlineAndStayWithinSizeLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/order/api/order/queryWithdrawalOrder", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var p = transport(server.getAddress().getPort()); p.setReadTimeoutMs(200);
            var gateway = new HttpHdPayPayoutGateway(p, payout(), json);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> assertThrows(HdPayGatewayException.class, () -> gateway.query("WD-TEST")));
        } finally { release.countDown(); server.stop(0); }

        HttpServer large = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        large.createContext("/api/order/api/order/queryWithdrawalOrder", exchange -> {
            byte[] body = new byte[70_000]; java.util.Arrays.fill(body, (byte)' ');
            exchange.sendResponseHeaders(200, body.length);
            try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
        });
        large.start();
        try {
            var gateway = new HttpHdPayPayoutGateway(transport(large.getAddress().getPort()), payout(), json);
            assertThrows(HdPayGatewayException.class, () -> gateway.query("WD-TEST"));
        } finally { large.stop(0); }
    }

    HdPayProperties transport(int port) {
        HdPayProperties p = new HdPayProperties();
        p.setMode(HdPayProperties.Mode.PROVIDER);
        p.setBaseUrl("http://127.0.0.1:" + port + "/api/order");
        p.setAllowInsecureBaseUrlForTests(true);
        p.setCallbackBaseUrl("https://pay.example.com");
        p.setCallbackHosts(java.util.List.of("pay.example.com"));
        p.setMerchantId("123456");
        p.setMd5Key("fixture-key-not-a-real-secret");
        p.setServerIp("1.1.1.1");
        return p;
    }

    HdPayPayoutProperties payout() {
        HdPayPayoutProperties p = new HdPayPayoutProperties();
        return p;
    }

    @Test void createUsesWithdrawalContractAndAcceptanceIsNotSettlement() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server.createContext("/api/order/api/order/publicWithdrawal", exchange -> {
            captured.set(json.readValue(exchange.getRequestBody(), Map.class));
            byte[] body = "{\"code\":200,\"msg\":\"\",\"data\":\"\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var p = transport(server.getAddress().getPort());
            var gateway = new HttpHdPayPayoutGateway(p, payout(), json);
            assertDoesNotThrow(() -> gateway.create(new HdPayPayoutGateway.Request(
                    "WD-TEST", new BigDecimal("1000000"), "", "0123456789", "NGUYEN VAN A")));
            Map<String, Object> body = captured.get();
            assertEquals("BANK", body.get("payType"));
            assertEquals("BANKQR", p.getPayType()); // Pay-in configuration is independent.
            assertTrue(body.containsKey("bnkCode"));
            assertEquals("", body.get("bnkCode"));
            assertEquals("NGUYEN VAN A", body.get("name"));
            assertFalse(body.containsKey("cvv")); assertFalse(body.containsKey("expiry"));
            assertEquals("VN", body.get("countryCode"));
            assertEquals("1.1.1.1", body.get("ip"));
            assertEquals("0123456789", body.get("account"));
            assertEquals("https://pay.example.com/openapi/v1/payments/hdpay/payout/callback", body.get("callbackUrl"));
            assertFalse(body.containsKey("ifsc"));
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            body.forEach((k, v) -> fields.put(k, v.toString()));
            assertTrue(HdPaySigner.verify(fields, p.getMd5Key(), body.get("sign").toString()));
        } finally { server.stop(0); }
    }

    @Test void payoutUsesSharedProviderConfigurationWithoutSeparateFlags() {
        var transport = transport(8080); var payout = new HdPayPayoutProperties();
        assertTrue(transport.ready()); assertTrue(payout.ready(transport));
        transport.setMode(HdPayProperties.Mode.DISABLED); assertFalse(payout.ready(transport));
        transport.setMode(HdPayProperties.Mode.PROVIDER); transport.setMd5Key("");
        assertFalse(payout.ready(transport));
    }

    @Test void nonemptyOrMissingBankCodeNeverReachesProvider() {
        var gateway = new HttpHdPayPayoutGateway(transport(1), payout(), json);
        for (String code : new String[]{"VCB", "BANKQR", null}) {
            var error = assertThrows(HdPayGatewayException.class, () -> gateway.create(new HdPayPayoutGateway.Request("WD-TEST", new BigDecimal("1000000"), code, "0123456789", "NGUYEN VAN A")));
            assertTrue(error.getMessage().contains("BANK_CODE_MUST_BE_EMPTY"));
        }
    }

    @Test void callbackSupportsDocumentedStringAmountAndStatusButRejectsTampering() throws Exception {
        var p = transport(8080);
        Map<String, String> body = new java.util.LinkedHashMap<>(Map.of(
                "merchantId", "123456", "merchantOrderId", "WD-TEST", "orderId", "1234",
                "transAmt", "1000000.00", "orderStatus", "3", "signType", "MD5",
                "standbyObject", "{\"refNo\":\"bank-reference\"}"));
        body.put("sign", HdPaySigner.sign(body, p.getMd5Key()));
        var verifier = new HdPayPayoutCallbackVerifier(p);
        var result = verifier.verify(json.valueToTree(body));
        assertEquals(3, result.status());
        assertEquals(new BigDecimal("1000000.00"), result.amount());
        body.put("transAmt", "2000000.00");
        assertThrows(RuntimeException.class, () -> verifier.verify(json.valueToTree(body)));
    }

    @Test void statusTwoAndRefundAreNeverSuccess() {
        assertEquals(HdPayPayoutOutcome.PENDING, HdPayPayoutOutcome.from(1));
        assertEquals(HdPayPayoutOutcome.PENDING, HdPayPayoutOutcome.from(2));
        assertEquals(HdPayPayoutOutcome.PAID, HdPayPayoutOutcome.from(3));
        assertEquals(HdPayPayoutOutcome.FAILED, HdPayPayoutOutcome.from(4));
        assertEquals(HdPayPayoutOutcome.FAILED, HdPayPayoutOutcome.from(5));
        assertThrows(RuntimeException.class, () -> HdPayPayoutOutcome.from(0));
    }

    @Test void queryParsesDocumentedNumericTargetTypeAndRejectsUnknownTypes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> type = new AtomicReference<>("2");
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        server.createContext("/api/order/api/order/queryWithdrawalOrder", exchange -> {
            captured.set(json.readValue(exchange.getRequestBody(), Map.class));
            byte[] body = json.writeValueAsBytes(Map.of("code", 200, "data", Map.of(
                    "merchantId", "123456", "merchantOrderId", "WD-TEST", "orderId", "1794920973526048768",
                    "orderStatus", 5, "transAmt", 100, "withdrawalCard", "09635690716",
                    "withdrawalType", type.get(), "withdrawalName", "TEST")));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var p = transport(server.getAddress().getPort());
            p.setServerIp(""); // Missing submission configuration must never stop query recovery.
            assertFalse(payout().ready(p));
            var gateway = new HttpHdPayPayoutGateway(p, payout(), json);
            var response = gateway.query("WD-TEST");
            assertEquals("2", response.type());
            assertEquals(1794920973526048768L, response.providerOrderId());
            assertEquals(new BigDecimal("100"), response.amount());
            assertEquals("WD-TEST", captured.get().get("merchantOrderId"));
            for (String bad : java.util.List.of("BANK", "1", "USDT", "", "999")) {
                type.set(bad); assertThrows(HdPayGatewayException.class, () -> gateway.query("WD-TEST"));
            }
        } finally { server.stop(0); }
    }

    @Test void invalidServerAddressesCannotCreateButPayInRemainsReady() {
        var p = transport(1);
        var gateway = new HttpHdPayPayoutGateway(p, payout(), json);
        for (String ip : new String[]{null, "", "localhost", "https://1.1.1.1", "127.0.0.1", "10.0.0.1",
                "172.31.29.251", "192.168.1.1", "169.254.169.254", "100.64.0.1", "203.0.113.7",
                "0.0.0.0", "224.0.0.1", "255.255.255.255", "256.1.1.1", "01.1.1.1", "::1", "1.1.1.1,8.8.8.8"}) {
            p.setServerIp(ip);
            assertTrue(p.ready());
            assertFalse(payout().ready(p));
            var error = assertThrows(HdPayGatewayException.class, () -> gateway.create(
                    new HdPayPayoutGateway.Request("WD-TEST", new BigDecimal("1000000"), "", "0123456789", "NGUYEN VAN A")));
            assertEquals("HDPAY_PAYOUT_CONFIGURATION_INCOMPLETE", error.getMessage());
        }
        p.setServerIp(" 1.1.1.1 ");
        assertEquals("1.1.1.1", payout().serverIp(p));
    }

    @Test void createResponseEvidenceNeverLogsRecipientSecretSignatureOrArbitraryProviderText() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HttpHdPayPayoutGateway.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var message = new AtomicReference<>("该ip禁止访问");
        var signature = new AtomicReference<String>();
        server.createContext("/api/order/api/order/publicWithdrawal", exchange -> {
            var fields = json.readValue(exchange.getRequestBody(), Map.class);
            signature.set(fields.get("sign").toString());
            byte[] body = json.writeValueAsBytes(Map.of("code", 408, "msg", message.get()));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var p = transport(server.getAddress().getPort());
            var gateway = new HttpHdPayPayoutGateway(p, payout(), json);
            var request = new HdPayPayoutGateway.Request("WD-TEST", new BigDecimal("1000000"), "", "0123456789", "NGUYEN VAN A");
            assertThrows(HdPayGatewayException.class, () -> gateway.create(request));
            message.set("echo 0123456789 NGUYEN VAN A\nunsafe log content");
            assertThrows(HdPayGatewayException.class, () -> gateway.create(request));
            String output = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(output.contains("order=WD-TEST payType=BANK serverIp=1.1.1.1 amountVnd=1000000"));
            assertTrue(output.contains("httpStatus=200 bodySha256="));
            assertTrue(output.contains("providerCode=408 reason=IP_NOT_ALLOWED"));
            assertTrue(output.contains("reason=UNCLASSIFIED_REJECTION"));
            for (String secret : new String[]{request.account(), request.holder(), p.getMd5Key(), signature.get(), "unsafe log content"})
                assertFalse(output.contains(secret));
        } finally { server.stop(0); logger.detachAppender(logs); logs.stop(); }
    }
}
