package ffdd.opsconsole.finance.hdpay;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class HdPayPayoutCallbackVerifier {
    private final HdPayProperties transport;
    private static final Set<String> FIELDS = Set.of("merchantId", "orderId", "transAmt", "merchantOrderId",
            "orderStatus", "standbyObject", "remark", "signType", "sign");
    public record Callback(String merchantOrderId, long providerOrderId, int status, BigDecimal amount, String hash) {}

    public Callback verify(JsonNode body) {
        if (!transport.connectionReady() || body == null || !body.isObject()) throw invalid();
        Map<String, String> fields = new LinkedHashMap<>();
        body.fields().forEachRemaining(e -> {
            if (!FIELDS.contains(e.getKey()) || !(e.getValue().isTextual() || e.getValue().isNumber())) throw invalid();
            String value = e.getValue().asText();
            if (value.length() > 4096) throw invalid();
            fields.put(e.getKey(), value);
        });
        if (!"MD5".equals(fields.get("signType")) || !transport.getMerchantId().equals(fields.get("merchantId"))
                || !HdPaySigner.verify(fields, transport.getMd5Key(), fields.get("sign"))) throw invalid();
        try {
            String order = fields.get("merchantOrderId");
            long provider = Long.parseLong(fields.get("orderId"));
            int status = Integer.parseInt(fields.get("orderStatus"));
            if (order == null || !order.matches("WD-[A-Za-z0-9]{1,60}") || provider <= 0) throw invalid();
            HdPayPayoutOutcome.from(status);
            BigDecimal amount = HttpHdPayPayoutGateway.amount(fields.get("transAmt"));
            // Semantic digest: no recipient, secret, signature or free text is stored in the inbox.
            String hash = HdPayPayoutDigest.sha(
                    order + "|" + provider + "|" + status + "|" + amount.stripTrailingZeros().toPlainString());
            return new Callback(order, provider, status, amount, hash);
        } catch (IllegalArgumentException ex) { throw invalid(); }
    }
    private RuntimeException invalid() { return HttpHdPayPayoutGateway.invalid("CALLBACK_INVALID"); }
}
