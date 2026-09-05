package ffdd.opsconsole.finance.hdpay;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class HdPayCallbackVerifier {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "merchantId", "orderId", "transAmt", "createTime", "merchantOrderId",
            "orderStatus", "payTime", "standbyObject", "remark", "signType", "sign");
    private final HdPayProperties properties;

    public HdPayCallbackVerifier(HdPayProperties properties) {
        this.properties = properties;
    }

    public VerifiedCallback verify(JsonNode body) {
        if (!properties.providerMode() || !properties.ready()) {
            throw new BizException(503, "HDPAY_CALLBACK_DISABLED");
        }
        if (body == null || !body.isObject()) invalid("HDPAY_CALLBACK_BODY_INVALID");
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            if (!ALLOWED_FIELDS.contains(names.next())) invalid("HDPAY_CALLBACK_FIELD_UNSUPPORTED");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> {
            if (!"sign".equals(entry.getKey()) && !entry.getValue().isNull()) {
                fields.put(entry.getKey(), "transAmt".equals(entry.getKey()) && entry.getValue().isNumber()
                        ? entry.getValue().decimalValue().setScale(2).toPlainString()
                        : scalar(entry.getValue()));
            }
        });
        String merchantId = requiredText(body, "merchantId", 32);
        String merchantOrderId = requiredToken(body, "merchantOrderId", 64);
        String orderId = requiredToken(body, "orderId", 64);
        String signType = requiredText(body, "signType", 16);
        String sign = requiredText(body, "sign", 64);
        int orderStatus = requiredInteger(body, "orderStatus");
        BigDecimal transAmt = requiredAmount(body, "transAmt");
        if (!merchantId.equals(properties.getMerchantId())
                || !"MD5".equalsIgnoreCase(signType)
                || !Set.of(1, 3, 4, 5).contains(orderStatus)
                || !HdPaySigner.verify(fields, properties.getMd5Key(), sign)) {
            invalid("HDPAY_CALLBACK_SIGNATURE_INVALID");
        }
        return new VerifiedCallback(
                merchantOrderId,
                orderId,
                orderStatus,
                transAmt,
                optionalText(body, "createTime", 32),
                optionalText(body, "payTime", 32),
                sign);
    }

    private String scalar(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isIntegralNumber()) return value.bigIntegerValue().toString();
        if (value.isFloatingPointNumber()) return value.decimalValue().toPlainString();
        if (value.isBoolean()) return String.valueOf(value.booleanValue());
        invalid("HDPAY_CALLBACK_FIELD_INVALID");
        return "";
    }

    private String requiredToken(JsonNode body, String name, int max) {
        String value = requiredText(body, name, max);
        if (!value.matches("[A-Za-z0-9_-]+")) invalid("HDPAY_CALLBACK_FIELD_INVALID");
        return value;
    }

    private String requiredText(JsonNode body, String name, int max) {
        String value = optionalText(body, name, max);
        if (value == null || value.isEmpty()) invalid("HDPAY_CALLBACK_FIELD_REQUIRED");
        return value;
    }

    private String optionalText(JsonNode body, String name, int max) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        String result = scalar(value);
        if (result.length() > max) invalid("HDPAY_CALLBACK_FIELD_INVALID");
        return result;
    }

    private int requiredInteger(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            invalid("HDPAY_CALLBACK_FIELD_INVALID");
        }
        return value.intValue();
    }

    private BigDecimal requiredAmount(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isNumber()) invalid("HDPAY_CALLBACK_FIELD_INVALID");
        BigDecimal amount = value.decimalValue();
        if (amount.signum() <= 0 || amount.scale() > 2) invalid("HDPAY_CALLBACK_FIELD_INVALID");
        return amount;
    }

    private void invalid(String message) {
        throw new BizException(400, message);
    }

    public record VerifiedCallback(
            String merchantOrderId,
            String orderId,
            int orderStatus,
            BigDecimal transAmt,
            String createTime,
            String payTime,
            String sign) {}
}
