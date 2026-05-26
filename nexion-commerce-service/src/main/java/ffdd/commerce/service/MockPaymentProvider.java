package ffdd.commerce.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.dto.PaymentCallbackRequest;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import ffdd.common.exception.BizException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MockPaymentProvider implements PaymentProvider {
    private static final String PROVIDER = "MOCK";
    private static final String HEADER_TIMESTAMP = "X-Nexion-Payment-Timestamp";
    private static final String HEADER_NONCE = "X-Nexion-Payment-Nonce";
    private static final String HEADER_SIGNATURE = "X-Nexion-Payment-Signature";

    private final ObjectMapper objectMapper;
    private final String secret;
    private final String checkoutBaseUrl;
    private final long checkoutTtlMinutes;
    private final long signatureWindowSeconds;
    private final Clock clock;

    public MockPaymentProvider(
            ObjectMapper objectMapper,
            @Value("${nexion.payment.mock.secret:nexion-mock-payment-secret}") String secret,
            @Value("${nexion.payment.mock.checkout-base-url:https://mock-pay.nexion.local/checkout}") String checkoutBaseUrl,
            @Value("${nexion.payment.mock.checkout-ttl-minutes:15}") long checkoutTtlMinutes,
            @Value("${nexion.payment.mock.signature-window-seconds:300}") long signatureWindowSeconds) {
        this(objectMapper, secret, checkoutBaseUrl, checkoutTtlMinutes, signatureWindowSeconds, Clock.systemDefaultZone());
    }

    MockPaymentProvider(
            ObjectMapper objectMapper,
            String secret,
            String checkoutBaseUrl,
            long checkoutTtlMinutes,
            long signatureWindowSeconds,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.checkoutBaseUrl = checkoutBaseUrl;
        this.checkoutTtlMinutes = Math.max(1, checkoutTtlMinutes);
        this.signatureWindowSeconds = Math.max(1, signatureWindowSeconds);
        this.clock = clock;
    }

    @Override
    public String code() {
        return PROVIDER;
    }

    @Override
    public PaymentSession createSession(CommerceOrder order, String paymentNo, PaymentCheckoutRequest request) {
        String providerPaymentId = "MOCK-PAY-" + paymentNo.substring(Math.max(0, paymentNo.length() - 12));
        String checkoutUrl = checkoutBaseUrl
                + "?paymentNo=" + encode(paymentNo)
                + "&orderNo=" + encode(order.getOrderNo())
                + "&amountUsdt=" + encode(order.getAmountUsdt().toPlainString());
        LocalDateTime expiresAt = LocalDateTime.now(clock).plusMinutes(checkoutTtlMinutes);
        return new PaymentSession(providerPaymentId, checkoutUrl, expiresAt);
    }

    @Override
    public PaymentProviderCallback parseAndVerifyCallback(Map<String, String> headers, String rawBody) {
        if (!StringUtils.hasText(rawBody)) {
            throw new BizException(401, "Payment callback payload is required");
        }
        String timestamp = header(headers, HEADER_TIMESTAMP);
        String nonce = header(headers, HEADER_NONCE);
        String signature = header(headers, HEADER_SIGNATURE);
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            throw new BizException(401, "Missing payment callback signature headers");
        }
        long callbackTimestamp;
        try {
            callbackTimestamp = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new BizException(401, "Invalid payment callback timestamp");
        }
        long now = Instant.now(clock).getEpochSecond();
        if (Math.abs(now - callbackTimestamp) > signatureWindowSeconds) {
            throw new BizException(401, "Payment callback signature timestamp expired");
        }
        String stringToSign = PROVIDER + "\n" + timestamp + "\n" + nonce + "\n" + sha256(rawBody);
        String expected = hmacSha256(secret, stringToSign);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(401, "Invalid payment callback signature");
        }
        PaymentCallbackRequest request = parse(rawBody);
        requireText(request.getEventId(), "Payment callback eventId is required");
        requireText(request.getPaymentNo(), "Payment callback paymentNo is required");
        requireText(request.getProviderPaymentId(), "Payment callback providerPaymentId is required");
        requireText(request.getOrderNo(), "Payment callback orderNo is required");
        requireText(request.getStatus(), "Payment callback status is required");
        if (request.getAmountUsdt() == null) {
            throw new BizException("Payment callback amount is required");
        }
        return new PaymentProviderCallback(
                request.getEventId(),
                request.getPaymentNo(),
                request.getProviderPaymentId(),
                request.getOrderNo(),
                request.getStatus(),
                request.getAmountUsdt(),
                StringUtils.hasText(request.getCurrency()) ? request.getCurrency() : "USDT",
                request.getFailureReason());
    }

    private PaymentCallbackRequest parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, PaymentCallbackRequest.class);
        } catch (JsonProcessingException ex) {
            throw new BizException("Payment callback payload is invalid");
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(message);
        }
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException("Unable to verify payment callback signature");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException("Unable to hash payment callback payload");
        }
    }
}
