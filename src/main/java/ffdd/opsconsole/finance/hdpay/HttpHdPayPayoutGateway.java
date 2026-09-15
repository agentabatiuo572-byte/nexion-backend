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
import java.util.List;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** No retry here. After *any* create attempt, recovery is query-only using the same merchant order. */
@Component
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
        if (!payout.getBankCodes().contains(request.bankCode())) throw invalid("BANK_NOT_ENABLED");
        String account = account(request.account());
        String holder = holder(request.holder());
        BigDecimal amount = amount(request.amount().toPlainString()).setScale(0, RoundingMode.UNNECESSARY);
        Map<String, String> fields = base(request.merchantOrderId());
        fields.put("account", account); fields.put("name", holder); fields.put("bnkCode", request.bankCode());
        fields.put("transAmt", amount.toPlainString()); fields.put("payType", "BANK");
        fields.put("countryCode", "VN"); fields.put("ip", payout.getClientIp());
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
        fields.put("sign", HdPaySigner.sign(fields, transport.getMd5Key()));
        try {
            var request = HttpRequest.newBuilder(URI.create(transport.getBaseUrl().replaceAll("/+$", "") + path))
                    .timeout(Duration.ofMillis(transport.getReadTimeoutMs()))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(fields))).build();
            var pending = client.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(transport.getReadTimeoutMs(), TimeUnit.MILLISECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            byte[] body = response.body();
            if (response.statusCode() != 200) throw invalid("HTTP_RESPONSE_INVALID");
            JsonNode result = json.readTree(body);
            if (result == null || !result.path("code").isIntegralNumber() || result.path("code").asInt() != 200)
                throw invalid("REQUEST_NOT_CONFIRMED");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw invalid("REQUEST_INTERRUPTED");
        } catch (IOException | ExecutionException | TimeoutException ex) { throw invalid("REQUEST_UNKNOWN"); }
    }

    /** Limit accumulation before allocation, and complete only after the entire body arrived. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) return;
            for (ByteBuffer item : items) {
                if (item.remaining() > 65_536 - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IOException("BODY_TOO_LARGE")); return;
                }
                byte[] chunk = new byte[item.remaining()]; item.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
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
