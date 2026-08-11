package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ComputeTaskProofVerifierTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);
    private static final byte[] KEY = "production-device-secret-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String NONCE = "a".repeat(64);

    @Test
    void randomHexCannotMintAProductionWalletReward() {
        var verifier = productionVerifier();
        var random = request("DEV-OTHER", "b".repeat(64), CLOCK.millis());

        assertThatThrownBy(() -> verifier.verify(7L, "CTA-1", 11L, "DEV-11", NONCE,
                LocalDateTime.of(2026, 8, 10, 12, 2), random))
                .isInstanceOf(BizException.class).hasMessageContaining("PROOF_SIGNATURE_INVALID");
    }

    @Test
    void signatureIsBoundToTheExactDeviceAndCannotCrossDevice() {
        var verifier = productionVerifier();
        var validForDevice11 = signedRequest(7L, "CTA-1", 11L, "DEV-11", CLOCK.millis());

        assertThatThrownBy(() -> verifier.verify(7L, "CTA-1", 12L, "DEV-12", NONCE,
                LocalDateTime.of(2026, 8, 10, 12, 2), validForDevice11))
                .isInstanceOf(BizException.class).hasMessageContaining("PROOF_SIGNATURE_INVALID");
    }

    @Test
    void expiredNonceIsRejectedBeforeSettlement() {
        var verifier = productionVerifier();
        var proof = signedRequest(7L, "CTA-1", 11L, "DEV-11", CLOCK.millis());

        assertThatThrownBy(() -> verifier.verify(7L, "CTA-1", 11L, "DEV-11", NONCE,
                LocalDateTime.of(2026, 8, 10, 11, 59), proof))
                .isInstanceOf(BizException.class).hasMessageContaining("PROOF_EXPIRED");
    }

    @Test
    void configuredExecutorSignatureAcceptsOnlyTheServerChallenge() {
        var verifier = productionVerifier();
        var proof = signedRequest(7L, "CTA-1", 11L, "DEV-11", CLOCK.millis());

        var result = verifier.verify(7L, "CTA-1", 11L, "DEV-11", NONCE,
                LocalDateTime.of(2026, 8, 10, 12, 2), proof);

        assertThat(result.sandbox()).isFalse();
        assertThat(result.proofHash()).matches("[a-f0-9]{64}");
    }

    @Test
    void sandboxModeCannotStartUnderAProductionProfile() {
        assertThatThrownBy(() -> ComputeTaskProofVerifier.requireSandboxProfile("SANDBOX", "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_PROFILE_REQUIRED");
        ComputeTaskProofVerifier.requireSandboxProfile("SANDBOX", "acceptance");
    }

    @Test
    void springManagedVerifierUsesTheRepositoryInjectionContract() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/ffdd/opsconsole/device/application/ComputeTaskProofVerifier.java"));

        assertThat(source).contains("@RequiredArgsConstructor",
                        "@Value(\"${nexion.compute-task.executor.mode:PRODUCTION}\")",
                        "private final Clock clock;",
                        "private final Environment environment;")
                .doesNotContain("@Autowired", "public ComputeTaskProofVerifier(");
    }

    private ComputeTaskProofVerifier productionVerifier() {
        return new ComputeTaskProofVerifier("PRODUCTION",
                "executor-1:DEV-11:" + Base64.getEncoder().encodeToString(KEY),
                "", "", "", 120_000, CLOCK, new MockEnvironment());
    }

    private AppTaskCompleteRequest signedRequest(long userId, String taskNo, long deviceId,
                                                  String deviceInstanceNo, long timestamp) {
        var unsigned = request(deviceInstanceNo, "", timestamp);
        String canonical = ComputeTaskProofVerifier.canonical(userId, taskNo, deviceId, deviceInstanceNo, unsigned);
        return request(deviceInstanceNo, hmac(canonical), timestamp);
    }

    private AppTaskCompleteRequest request(String ignoredDevice, String signature, long timestamp) {
        return new AppTaskCompleteRequest("c".repeat(64), "PRODUCTION", "executor-1",
                NONCE, timestamp, signature);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
