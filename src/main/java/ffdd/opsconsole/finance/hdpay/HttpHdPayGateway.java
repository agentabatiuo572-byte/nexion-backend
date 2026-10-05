package ffdd.opsconsole.finance.hdpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public final class HttpHdPayGateway implements HdPayGateway {
    private static final String CREATE_PATH = "/api/payOrder/publicCreatePayOrder";
    private static final String QUERY_PATH = "/api/payOrder/queryPayOrder";
    private final HdPayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private enum PublicReasonDisposition {
        NOT_APPLICABLE, MISSING, NON_TEXT, EMPTY, FILTERED, PRIVATE_ECHO, SAFE
    }

    private record PublicReason(String value, PublicReasonDisposition disposition) {}

    @Autowired
    public HttpHdPayGateway(HdPayProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildHttpClient(properties));
    }

    static HttpClient buildHttpClient(HdPayProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(100, properties.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER);
        String proxyHost = properties.getProxyHost() == null ? "" : properties.getProxyHost().trim();
        if (!proxyHost.isEmpty()) {
            int proxyPort = properties.getProxyPort();
            if (proxyPort < 1 || proxyPort > 65_535) {
                throw new IllegalArgumentException("HDPAY_PROXY_CONFIGURATION_INVALID");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return builder.build();
    }

    HttpHdPayGateway(HdPayProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public PayPage createPayOrder(CreatePayOrder order) {
        if (!properties.ready()) throw new HdPayGatewayException("HDPAY_CONFIGURATION_INCOMPLETE", false);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("ip", cleanIp(order.clientIp()));
        requestBody.put("merchantId", properties.getMerchantId().trim());
        requestBody.put("merchantOrderId", required(order.merchantOrderId(), "HDPAY_MERCHANT_ORDER_REQUIRED"));
        requestBody.put("payType", properties.getPayType().trim().toUpperCase(java.util.Locale.ROOT));
        requestBody.put("countryCode", properties.getCountryCode().trim().toUpperCase(java.util.Locale.ROOT));
        requestBody.put("transAmt", integerVnd(order.transAmt()));
        requestBody.put("callbackUrl", properties.callbackUrl());
        requestBody.put("sign", HdPaySigner.sign(stringFields(requestBody), properties.getMd5Key()));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + CREATE_PATH))
                    .timeout(Duration.ofMillis(Math.max(100, properties.getReadTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(requestBody)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            JsonNode root = null;
            boolean parsed = false;
            String reason = "UNCONFIRMED_RESPONSE";
            PublicReasonDisposition publicReasonDisposition = PublicReasonDisposition.NOT_APPLICABLE;
            try {
                try {
                    root = objectMapper.readTree(body);
                    parsed = true;
                } catch (IOException ex) {
                    reason = "INVALID_JSON";
                    // Preserve HTTP rejection for non-2xx even when its body is not JSON.
                    if (response.statusCode() >= 200 && response.statusCode() < 300) throw ex;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    reason = "HTTP_REJECTED";
                    throw new HdPayGatewayException("HDPAY_HTTP_" + response.statusCode(), false);
                }
                if (root == null || !root.isObject()) {
                    throw new HdPayGatewayException("HDPAY_CREATE_REJECTED", true);
                }
                JsonNode code = root.path("code");
                boolean numericCode = code.isIntegralNumber() && code.canConvertToInt();
                if (!numericCode && !(code.isTextual() && "200".equals(code.textValue()))) {
                    throw new HdPayGatewayException("HDPAY_CREATE_REJECTED", true);
                }
                if (numericCode && code.intValue() != 200) {
                    reason = safeRejectionReason(root);
                    PublicReason publicReason = createRejectionReason(root, requestBody);
                    publicReasonDisposition = publicReason.disposition();
                    throw new HdPayGatewayException("HDPAY_CREATE_EXPLICIT_REJECTED", false, null,
                            publicReason.value());
                }
                String page = root.path("data").isTextual() ? root.path("data").asText().trim() : "";
                if (!properties.isTrustedPaymentPage(page)) {
                    throw new HdPayGatewayException("HDPAY_PAYMENT_PAGE_UNTRUSTED", true);
                }
                reason = "ACCEPTED";
                return new PayPage(page);
            } finally {
                log.info("HDPay pay-in create response orderRef={} httpStatus={} providerCode={} codeType={} rootType={} dataType={} msgType={} bodySha256={} reason={} publicReasonDisposition={}",
                        "sha256:" + sha256(requestBody.get("merchantOrderId").toString()
                                .getBytes(StandardCharsets.UTF_8)).substring(0, 16),
                        response.statusCode(), safeProviderCode(root), responseFieldType(root, "code"),
                        parsed ? (root == null ? "EMPTY" : root.getNodeType().name()) : "INVALID_JSON",
                        responseFieldType(root, "data"), responseFieldType(root, "msg"), sha256(body),
                        reason, publicReasonDisposition);
            }
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new HdPayGatewayException("HDPAY_CREATE_TIMEOUT", true, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HdPayGatewayException("HDPAY_CREATE_INTERRUPTED", true, ex);
        } catch (IOException ex) {
            throw new HdPayGatewayException("HDPAY_CREATE_IO_ERROR", true, ex);
        }
    }

    // Diagnostics never emit arbitrary provider text, URLs, request fields or signatures.
    private static String safeProviderCode(JsonNode root) {
        JsonNode code = root == null ? null : root.get("code");
        if (code == null) return "MISSING";
        if (code.isIntegralNumber() && code.canConvertToInt()) return code.asText();
        if (code.isTextual() && code.asText().matches("-?[0-9]{1,10}")) return code.asText();
        return "INVALID";
    }

    private static String responseFieldType(JsonNode root, String field) {
        return root == null ? "MISSING" : root.path(field).getNodeType().name();
    }

    private static String safeRejectionReason(JsonNode root) {
        return switch (root.path("msg").asText("")) {
            case "该ip禁止访问" -> "IP_NOT_ALLOWED";
            default -> "UNCLASSIFIED_REJECTION";
        };
    }

    private PublicReason createRejectionReason(JsonNode root, Map<String, Object> request) {
        JsonNode message = root.get("msg");
        if (message == null) return new PublicReason("", PublicReasonDisposition.MISSING);
        if (!message.isTextual()) return new PublicReason("", PublicReasonDisposition.NON_TEXT);
        PublicReason result = filterPublicBusinessReason(message.textValue());
        String reason = result.value();
        if (reason.isEmpty()) return result;
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        for (String field : java.util.List.of("ip", "merchantId", "merchantOrderId", "callbackUrl", "sign")) {
            String value = String.valueOf(request.get(field)).toLowerCase(java.util.Locale.ROOT);
            if (!value.isBlank() && lower.contains(value)) return new PublicReason("", PublicReasonDisposition.PRIVATE_ECHO);
        }
        String key = properties.getMd5Key().toLowerCase(java.util.Locale.ROOT);
        return !key.isBlank() && lower.contains(key)
                ? new PublicReason("", PublicReasonDisposition.PRIVATE_ECHO) : result;
    }

    // Only short ordinary business prose may reach a public order response or audit detail.
    public static String publicBusinessReason(String value) {
        return filterPublicBusinessReason(value).value();
    }

    private static PublicReason filterPublicBusinessReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty()) return new PublicReason("", PublicReasonDisposition.EMPTY);
        if (reason.length() > 256
                || !reason.matches("[\\p{L}\\p{M}\\p{N} .,，。!！?？;；:：'’()（）%+\\-·]+")
                || reason.matches("(?i).*(password|secret|token|credential|authorization|bearer|signature|api[ -]?key|md5[ -]?key|private[ -]?key|account number|account name|holder|密钥|密码|令牌|账号|卡号|户名).*")
                || reason.matches("(?i).*\\b[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.[a-z]{2,}\\b.*")
                || reason.matches(".*[A-Za-z0-9]{32,}.*")
                || reason.matches(".*[0-9]{10,}.*")
                || reason.matches(".*[0-9]{1,3}(\\.[0-9]{1,3}){3}.*")) return new PublicReason("", PublicReasonDisposition.FILTERED);
        return new PublicReason(reason, PublicReasonDisposition.SAFE);
    }

    private static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override
    public PayOrder queryPayOrder(String merchantOrderId) {
        if (!properties.ready()) throw new HdPayGatewayException("HDPAY_CONFIGURATION_INCOMPLETE", false);
        String expectedOrderId = required(merchantOrderId, "HDPAY_MERCHANT_ORDER_REQUIRED");
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("merchantId", properties.getMerchantId().trim());
        requestBody.put("merchantOrderId", expectedOrderId);
        requestBody.put("sign", HdPaySigner.sign(stringFields(requestBody), properties.getMd5Key()));
        try {
            JsonNode root = send(QUERY_PATH, requestBody);
            JsonNode data = root.path("data");
            if (!data.isObject()
                    || !properties.getMerchantId().trim().equals(text(data, "merchantId"))
                    || !expectedOrderId.equals(text(data, "merchantOrderId"))) {
                throw new HdPayGatewayException("HDPAY_QUERY_IDENTITY_MISMATCH", false);
            }
            String providerOrderId = token(data, "orderId");
            JsonNode rawStatus = data.get("orderStatus");
            if (rawStatus == null || !rawStatus.isIntegralNumber() || !rawStatus.canConvertToInt()) {
                throw new HdPayGatewayException("HDPAY_QUERY_RESPONSE_INVALID", false);
            }
            int status = rawStatus.intValue();
            if (!java.util.Set.of(0, 1, 3, 4, 5).contains(status)) {
                throw new HdPayGatewayException("HDPAY_QUERY_RESPONSE_INVALID", false);
            }
            JsonNode rawAmount = data.get("transAmt");
            if (rawAmount == null || !rawAmount.isNumber()
                    || rawAmount.decimalValue().signum() <= 0
                    || rawAmount.decimalValue().scale() > 2) {
                throw new HdPayGatewayException("HDPAY_QUERY_RESPONSE_INVALID", false);
            }
            String payType = text(data, "payType").toUpperCase(java.util.Locale.ROOT);
            if (!properties.getPayType().trim().equalsIgnoreCase(payType)) {
                throw new HdPayGatewayException("HDPAY_QUERY_PAY_TYPE_MISMATCH", false);
            }
            String appLink = text(data, "appLink");
            if (!appLink.isEmpty() && !properties.isTrustedPaymentPage(appLink)) {
                throw new HdPayGatewayException("HDPAY_PAYMENT_PAGE_UNTRUSTED", false);
            }
            return new PayOrder(
                    expectedOrderId,
                    providerOrderId,
                    status,
                    rawAmount.decimalValue(),
                    payType,
                    appLink);
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new HdPayGatewayException("HDPAY_QUERY_TIMEOUT", false, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HdPayGatewayException("HDPAY_QUERY_INTERRUPTED", false, ex);
        } catch (IOException ex) {
            throw new HdPayGatewayException("HDPAY_QUERY_IO_ERROR", false, ex);
        }
    }

    private JsonNode send(String path, Map<String, Object> requestBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + path))
                .timeout(Duration.ofMillis(Math.max(100, properties.getReadTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(requestBody)))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HdPayGatewayException("HDPAY_HTTP_" + response.statusCode(), false);
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root == null || root.path("code").asInt(Integer.MIN_VALUE) != 200) {
            throw new HdPayGatewayException("HDPAY_QUERY_REJECTED", false);
        }
        return root;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText().trim() : "";
    }

    private String token(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isEmpty() || value.length() > 64 || !value.matches("[A-Za-z0-9_-]+")) {
            throw new HdPayGatewayException("HDPAY_QUERY_RESPONSE_INVALID", false);
        }
        return value;
    }

    private Map<String, String> stringFields(Map<String, Object> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            if (value != null) result.put(name, value instanceof BigDecimal decimal
                    ? decimal.toPlainString() : String.valueOf(value));
        });
        return result;
    }

    private BigDecimal integerVnd(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new HdPayGatewayException("HDPAY_AMOUNT_INVALID", false);
        }
        try {
            return value.setScale(0, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new HdPayGatewayException("HDPAY_AMOUNT_INVALID", false, ex);
        }
    }

    private String cleanIp(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.length() > 64 || !candidate.matches("[0-9A-Fa-f:.]+")) {
            throw new HdPayGatewayException("HDPAY_CLIENT_IP_INVALID", false);
        }
        return candidate;
    }

    private String required(String value, String message) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || candidate.length() > 64 || !candidate.matches("[A-Za-z0-9_-]+")) {
            throw new HdPayGatewayException(message, false);
        }
        return candidate;
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
