package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.mapper.JanusExecutorClaimNonceMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class JanusExecutorClaimVerifierTest {
    private static final long NOW = System.currentTimeMillis();
    private final JanusExecutorClaimNonceMapper mapper = mock(JanusExecutorClaimNonceMapper.class);
    private final Environment environment = mock(Environment.class);

    @Test
    void productionClaimRequiresConfiguredExecutorHmacAndDurableNonceReadback() throws Exception {
        byte[] key = "production-device-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
        JanusExecutorClaimVerifier verifier = verifier("PRODUCTION",
                "executor-1:device-1:" + Base64.getEncoder().encodeToString(key));
        JanusExecutorClaimVerifier.Claim unsigned = claim("device-1", "executor-1", "");
        String signature = hmac(key, JanusExecutorClaimVerifier.canonical(42L, unsigned));
        JanusExecutorClaimVerifier.Claim signed = claim("device-1", "executor-1", signature);
        String hash = sha256(JanusExecutorClaimVerifier.canonical(42L, signed) + "\n" + signature);
        when(mapper.findClaimHash("executor-1", "a".repeat(32))).thenReturn(null, hash);
        when(mapper.claim(any(), any(), any(), any(), anyLong())).thenReturn(1);

        JanusExecutorClaimVerifier.Verification result = verifier.verify(42L, signed);

        assertThat(result.accepted()).isTrue();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void configuredExecutorCannotClaimAnotherDevice() {
        JanusExecutorClaimVerifier verifier = verifier("PRODUCTION", "executor-1:device-1:" +
                Base64.getEncoder().encodeToString("production-device-key-32-bytes!!".getBytes(StandardCharsets.UTF_8)));

        JanusExecutorClaimVerifier.Verification result = verifier.verify(42L,
                claim("device-2", "executor-1", "b".repeat(64)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_EXECUTOR_DEVICE_BINDING_MISMATCH");
        verify(mapper, never()).claim(any(), any(), any(), any(), anyLong());
    }

    @Test
    void nonceReplayWithDifferentPersistedHashIsRejected() throws Exception {
        byte[] key = "production-device-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
        JanusExecutorClaimVerifier.Claim unsigned = claim("device-1", "executor-1", "");
        String signature = hmac(key, JanusExecutorClaimVerifier.canonical(42L, unsigned));
        when(mapper.findClaimHash("executor-1", "a".repeat(32))).thenReturn("c".repeat(64));

        JanusExecutorClaimVerifier.Verification result = verifier("PRODUCTION",
                "executor-1:device-1:" + Base64.getEncoder().encodeToString(key)).verify(42L,
                claim("device-1", "executor-1", signature));

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_EXECUTOR_CLAIM_REPLAYED");
    }

    @Test
    void commandAuthorizationIsDeviceBoundAndCoversTheImmutableDigest() throws Exception {
        byte[] key = "production-device-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
        JanusExecutorClaimVerifier verifier = verifier("PRODUCTION",
                "executor-1:device-1:" + Base64.getEncoder().encodeToString(key));

        String authorization = verifier.authorizeCommand("executor-1", "device-1", "d".repeat(64));

        assertThat(authorization).isEqualTo(hmac(key, String.join("\n", "JANUS_COMMAND_AUTH_V1",
                "device-1", "executor-1", "d".repeat(64))));
        assertThat(verifier.authorizeCommand("executor-1", "device-1", "e".repeat(64)))
                .isNotEqualTo(authorization);
    }

    @Test
    void sandboxClaimRejectsMultipleActiveProfiles() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test", "dev"});
        JanusExecutorClaimVerifier verifier = new JanusExecutorClaimVerifier(mapper, environment,
                "SANDBOX", "", 120_000L, java.util.Optional.empty());

        JanusExecutorClaimVerifier.Verification result = verifier.verify(42L,
                claim("device-1", "sandbox", "sandbox-token"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_SANDBOX_CLAIM_ISOLATION_MISMATCH");
    }

    @Test
    void sandboxClaimRejectsTheFormerStaticFixtureTokenWithoutEnrollment() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        JanusExecutorClaimVerifier verifier = new JanusExecutorClaimVerifier(mapper, environment,
                "SANDBOX", "", 120_000L, java.util.Optional.empty());

        JanusExecutorClaimVerifier.Verification result = verifier.verify(42L,
                claim("device-1", "sandbox", "sandbox-token"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_SANDBOX_CLAIM_ISOLATION_MISMATCH");
        verify(mapper, never()).claim(any(), any(), any(), any(), anyLong());
    }

    @Test
    void sandboxClaimAcceptsServerEnrollmentForAUserNotPresentInStaticFixtures() {
        var sandboxEnvironment = new MockEnvironment() {
            @Override public String[] getActiveProfiles() { return new String[]{"dev"}; }
        };
        var enrollment = new JanusSandboxEnrollmentService(sandboxEnvironment, "SANDBOX", "approved",
                60_000L, System::currentTimeMillis, new java.security.SecureRandom());
        var issued = enrollment.issue(91L, "device-91");
        JanusExecutorClaimVerifier verifier = new JanusExecutorClaimVerifier(mapper, sandboxEnvironment,
                "SANDBOX", "", 120_000L, java.util.Optional.of(enrollment));
        when(mapper.claim(any(), any(), any(), any(), anyLong())).thenReturn(1);
        var signed = claim("device-91", "sandbox", issued.token());
        String expectedHash;
        try {
            expectedHash = sha256(JanusExecutorClaimVerifier.canonical(91L, signed) + "\n" + issued.token());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(mapper.findClaimHash("sandbox", "a".repeat(32))).thenReturn(null, expectedHash);

        assertThat(verifier.verify(91L, signed).accepted()).isTrue();
        assertThat(verifier.verify(92L, signed).accepted()).isFalse();
    }

    private JanusExecutorClaimVerifier verifier(String mode, String productionKeys) {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        return new JanusExecutorClaimVerifier(mapper, environment, mode, productionKeys, 120_000L,
                java.util.Optional.empty());
    }

    private JanusExecutorClaimVerifier.Claim claim(String deviceId, String executorId, String signature) {
        return new JanusExecutorClaimVerifier.Claim(deviceId, executorId, "a".repeat(32), NOW, signature,
                "GET", "/api/app/janus/commands/pending?deviceId=" + deviceId,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    private static String hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
