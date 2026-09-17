package ffdd.opsconsole.finance.hdpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** No retry here. After *any* create attempt, recovery is query-only using the same merchant order. */
@Component @Slf4j
public final class HttpHdPayPayoutGateway implements HdPayPayoutGateway {
    private final HdPayProperties transport;
    private final HdPayPayoutProperties payout;
    private final ObjectMapper json;
    private final HttpClient client;

    public HttpHdPayPayoutGateway(HdPayProperties transport, HdPayPayoutProperties payout, ObjectMapper json) {
        this.transport = transport; this.payout = payout; this.json = json;
        this.client = HttpHdPayGateway.buildHttpClient(transport);
    }

    @Override public void create(Request request) {
        if (!payout.ready(transport)) throw invalid("CONFIGURATION_INCOMPLETE");
        if (!"".equals(request.bankCode())) throw invalid("BANK_CODE_MUST_BE_EMPTY");
        String account = account(request.account());
        String holder = holder(request.holder());
        BigDecimal amount = amount(request.amount().toPlainString()).setScale(0, RoundingMode.UNNECESSARY);
        Map<String, String> fields = base(request.merchantOrderId());
        fields.put("account", account); fields.put("name", holder); fields.put("bnkCode", "");
        fields.put("transAmt", amount.toPlainString()); fields.put("payType", HdPayPayoutProperties.PAY_TYPE);
        fields.put("countryCode", "VN"); fields.put("ip", payout.serverIp(transport));
        fields.put("callbackUrl", payout.callbackUrl(transport));
        send("/api/order/publicWithdrawal", fields);
    }

    @Override public Order query(String merchantOrderId) {
        // Closing the submission gate must never prevent reconciliation of existing obligations.
        if (!transport.connectionReady()) throw invalid("CONFIGURATION_INCOMPLETE");
        JsonNode data = send("/api/order/queryWithdrawalOrder", base(merchantOrderId)).path("data");
        if (!data.isObject() || !transport.getMerchantId().equals(text(data, "merchantId"))
                || !merchantOrderId.equals(text(data, "merchantOrderId"))) throw invalid("QUERY_IDENTITY_MISMATCH");
        try {
            long providerId = Long.parseLong(text(data, "orderId"));
            int status = Integer.parseInt(text(data, "orderStatus"));
            if (providerId <= 0) throw invalid("QUERY_IDENTITY_MISMATCH");
            HdPayPayoutOutcome.from(status);
            String type = text(data, "withdrawalType");
            // Pin the documented query contract. Do not confuse its numeric account type with create.payType.
            // Any provider contract change must be reviewed; unknown types never settle money.
            if (!"2".equals(type)) throw invalid("QUERY_TYPE_INVALID");
            return new Order(merchantOrderId, providerId, status, amount(text(data, "transAmt")),
                    account(text(data, "withdrawalCard")), holder(text(data, "withdrawalName")), type);
        } catch (IllegalArgumentException ex) { throw invalid("QUERY_RESPONSE_INVALID"); }
    }

    private JsonNode send(String path, Map<String, String> fields) {
        boolean create = "/api/order/publicWithdrawal".equals(path);
        fields.put("sign", HdPaySigner.sign(fields, transport.getMd5Key()));
        try {
            var request = HttpRequest.newBuilder(URI.create(transport.getBaseUrl().replaceAll("/+$", "") + path))
                    .timeout(Duration.ofMillis(transport.getReadTimeoutMs()))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(fields))).build();
            if (create) log.info("HDPay payout create started order={} payType={} serverIp={} amountVnd={}",
                    fields.get("merchantOrderId"), fields.get("payType"), fields.get("ip"), fields.get("transAmt"));
            var pending = client.sendAsync(request, ignored -> new HdPayPayoutBodySubscriber());
            HttpResponse<byte[]> response;
            try { response = pending.get(transport.getReadTimeoutMs(), TimeUnit.MILLISECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            byte[] body = response.body();
            if (create) log.info("HDPay payout create HTTP response order={} httpStatus={} bodySha256={}",
                    fields.get("merchantOrderId"), response.statusCode(), java.util.HexFormat.of().formatHex(sha256(body)));
            if (response.statusCode() != 200) throw invalid("HTTP_RESPONSE_INVALID");
            JsonNode result = json.readTree(body);
            if (create) log.info("HDPay payout create business response order={} providerCode={} reason={}",
                    fields.get("merchantOrderId"), result != null && result.path("code").isIntegralNumber()
                            ? result.path("code").asText() : "INVALID", safeResponseReason(result));
            if (result == null || !result.path("code").isIntegralNumber() || result.path("code").asInt() != 200)
                throw invalid("REQUEST_NOT_CONFIRMED");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw invalid("REQUEST_INTERRUPTED");
        } catch (IOException | ExecutionException | TimeoutException ex) { throw invalid("REQUEST_UNKNOWN"); }
    }

    // Provider text may echo the account/name; log only known fixed reasons, never the raw body or message.
    private static String safeResponseReason(JsonNode result) {
        if (result == null) return "INVALID";
        if (result.path("code").isIntegralNumber() && result.path("code").asInt() == 200) return "ACCEPTED";
        return switch (result.path("msg").asText("")) {
            case "该ip禁止访问" -> "IP_NOT_ALLOWED";
            case "代付订单不存在" -> "ORDER_NOT_FOUND";
            default -> "UNCLASSIFIED_REJECTION";
        };
    }

    private static byte[] sha256(byte[] body) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(body); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Map<String, String> base(String order) {
        if (order == null || !order.matches("WD-[A-Za-z0-9]{1,60}")) throw invalid("ORDER_INVALID");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("merchantId", transport.getMerchantId()); fields.put("merchantOrderId", order);
        return fields;
    }

    static String text(JsonNode data, String field) {
        JsonNode v = data.get(field);
        if (v == null || !(v.isTextual() || v.isNumber())) throw invalid("RESPONSE_FIELD_INVALID");
        String text = v.asText();
        if (text.length() > 1024) throw invalid("RESPONSE_FIELD_INVALID");
        return text;
    }
    public static String account(String value) {
        if (value == null || !value.matches("[0-9]{6,32}")) throw invalid("ACCOUNT_INVALID");
        return value;
    }
    public static String holder(String value) {
        if (value == null || !value.equals(value.trim()) || value.length() < 2 || value.length() > 100
                || !value.matches("[\\p{L}\\p{M} .'-]+")) throw invalid("HOLDER_INVALID");
        return value;
    }
    static BigDecimal amount(String value) {
        if (value == null || !value.matches("[0-9]{1,15}(\\.[0-9]{1,2})?")) throw invalid("AMOUNT_INVALID");
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) throw invalid("AMOUNT_INVALID");
        return amount;
    }
    static HdPayGatewayException invalid(String reason) { return new HdPayGatewayException("HDPAY_PAYOUT_" + reason, false); }
}
