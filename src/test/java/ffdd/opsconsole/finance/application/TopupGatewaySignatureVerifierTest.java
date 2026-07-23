package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TopupGatewaySignatureVerifierTest {
    private static final String SECRET = "d1-test-secret-32-bytes-minimum-value";
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void acceptsValidRawBodyHmacAndRejectsTamperingOrExpiredTimestamp() throws Exception {
        TopupGatewaySignatureVerifier verifier = new TopupGatewaySignatureVerifier(SECRET);
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String body = "{\"eventId\":\"evt-100\",\"amount\":10}";
        String signature = sign(timestamp + "." + body);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThat(verifier.verifyAt(timestamp, "sha256=" + signature, body, clock).valid()).isTrue();
        assertThat(verifier.verifyAt(timestamp, signature, body + " ", clock).reason())
                .isEqualTo("PAYMENT_SIGNATURE_INVALID");
        assertThat(verifier.verifyAt(
                        String.valueOf(NOW.minusSeconds(301).getEpochSecond()), signature, body, clock).reason())
                .isEqualTo("PAYMENT_SIGNATURE_EXPIRED");
    }

    @Test
    void failsClosedWhenSecretIsNotConfiguredStronglyEnough() {
        TopupGatewaySignatureVerifier verifier = new TopupGatewaySignatureVerifier("short");

        assertThat(verifier.verifyAt("1", "00", "{}", Clock.fixed(NOW, ZoneOffset.UTC)).reason())
                .isEqualTo("PAYMENT_INGESTION_UNCONFIGURED");
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
