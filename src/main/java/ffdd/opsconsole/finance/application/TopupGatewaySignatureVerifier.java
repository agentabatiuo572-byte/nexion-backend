package ffdd.opsconsole.finance.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class TopupGatewaySignatureVerifier {
    static final long MAX_SKEW_SECONDS = 300;
    @Value("${nexion.finance.provider-ingestion-secret:}")
    private final String secret;

    public Verification verify(String timestampHeader, String signatureHeader, String rawBody) {
        return verifyAt(timestampHeader, signatureHeader, rawBody, Clock.systemUTC());
    }

    Verification verifyAt(String timestampHeader, String signatureHeader, String rawBody, Clock clock) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            return new Verification(false, "PAYMENT_INGESTION_UNCONFIGURED");
        }
        if (!StringUtils.hasText(timestampHeader) || !StringUtils.hasText(signatureHeader) || rawBody == null) {
            return new Verification(false, "PAYMENT_SIGNATURE_REQUIRED");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException ex) {
            return new Verification(false, "PAYMENT_TIMESTAMP_INVALID");
        }
        if (Math.abs(clock.instant().getEpochSecond() - timestamp) > MAX_SKEW_SECONDS) {
            return new Verification(false, "PAYMENT_SIGNATURE_EXPIRED");
        }
        String supplied = signatureHeader.trim();
        if (supplied.regionMatches(true, 0, "sha256=", 0, 7)) {
            supplied = supplied.substring(7);
        }
        byte[] suppliedBytes;
        try {
            suppliedBytes = java.util.HexFormat.of().parseHex(supplied);
        } catch (IllegalArgumentException ex) {
            return new Verification(false, "PAYMENT_SIGNATURE_INVALID");
        }
        byte[] expected = hmac(secretBytes, timestampHeader.trim() + "." + rawBody);
        return MessageDigest.isEqual(expected, suppliedBytes)
                ? new Verification(true, "VERIFIED")
                : new Verification(false, "PAYMENT_SIGNATURE_INVALID");
    }

    private byte[] hmac(byte[] secretBytes, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC_SHA256_UNAVAILABLE", ex);
        }
    }

    public record Verification(boolean valid, String reason) {
    }
}
