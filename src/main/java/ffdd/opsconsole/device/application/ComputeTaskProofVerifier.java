package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.janus.application.JanusSandboxEnrollmentService;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Fail-closed completion proof gate. Browser sessions never possess production executor keys. */
@Service
@RequiredArgsConstructor
public class ComputeTaskProofVerifier {
    private static final Set<String> SANDBOX_PROFILES = Set.of("DEV", "TEST");
    private static final Pattern EXECUTOR_ID = Pattern.compile("^[A-Za-z0-9._:-]{3,128}$");

    @Value("${nexion.compute-task.executor.mode:PRODUCTION}")
    private final String mode;
    @Value("${nexion.compute-task.executor.production-keys:}")
    private final String productionKeys;
    @Value("${nexion.compute-task.executor.max-skew-ms:120000}")
    private final long maxSkewMs;
    private final Clock clock;
    private final Environment environment;
    private final JanusSandboxEnrollmentService sandboxEnrollmentService;

    @PostConstruct
    void validateProfile() {
        requireSandboxProfile(mode, environment.getActiveProfiles());
    }

    public Verification verify(long userId, String taskNo, long deviceId, String deviceInstanceNo,
                               String expectedNonce, LocalDateTime proofExpiresAt,
                               AppTaskCompleteRequest request) {
        Map<String, Executor> productionExecutors = parseExecutors(productionKeys);
        if (request == null || !hex64(request.resultHash()) || !hex64(request.proofNonce())
                || !constantTime(expectedNonce, trim(request.proofNonce()))
                || request.proofTimestamp() == null || trim(request.executorId()).isEmpty()
                || trim(request.proofSignature()).isEmpty()) {
            throw untrusted("TASK_ASSIGNMENT_PROOF_UNTRUSTED");
        }
        long timestamp = request.proofTimestamp();
        long nowMs = clock.millis();
        if (proofExpiresAt == null || nowMs > proofExpiresAt.toInstant(ZoneOffset.UTC).toEpochMilli()
                || Math.abs(nowMs - timestamp) > Math.max(1_000, maxSkewMs)) {
            throw untrusted("TASK_ASSIGNMENT_PROOF_EXPIRED");
        }
        String proofMode = upper(request.proofMode());
        String canonical = canonical(userId, taskNo, deviceId, deviceInstanceNo, request);
        if ("PRODUCTION".equals(proofMode)) {
            if (!"PRODUCTION".equals(mode)) throw untrusted("TASK_ASSIGNMENT_PRODUCTION_PROOF_DISABLED");
            Executor executor = productionExecutors.get(trim(request.executorId()));
            if (executor == null || !executor.deviceInstanceNo().equals(deviceInstanceNo)
                    || !constantTime(hmac(executor.key(), canonical), trim(request.proofSignature()).toLowerCase(Locale.ROOT))) {
                throw untrusted("TASK_ASSIGNMENT_PROOF_SIGNATURE_INVALID");
            }
            return new Verification(false, sha256(canonical + "\n" + trim(request.proofSignature())));
        }
        if ("SANDBOX".equals(proofMode)) {
            if (!"SANDBOX".equals(mode) || !"sandbox".equals(trim(request.executorId()))
                    || !sandboxEnrollmentService.verify(userId, deviceInstanceNo, trim(request.proofSignature()))) {
                throw untrusted("TASK_ASSIGNMENT_SANDBOX_ISOLATION_MISMATCH");
            }
            return new Verification(true, sha256(canonical + "\n" + trim(request.proofSignature())));
        }
        throw untrusted("TASK_ASSIGNMENT_PROOF_MODE_INVALID");
    }

    public String sourceEnvironment() { return "SANDBOX".equals(mode) ? "SANDBOX" : "PRODUCTION"; }

    static void requireSandboxProfile(String mode, String... activeProfiles) {
        String[] normalized = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .map(ComputeTaskProofVerifier::upper).filter(profile -> !profile.isEmpty()).distinct()
                .toArray(String[]::new);
        if ("SANDBOX".equals(upper(mode))
                && (normalized.length != 1 || !SANDBOX_PROFILES.contains(normalized[0]))) {
            throw new IllegalStateException("TASK_ASSIGNMENT_SANDBOX_PROFILE_REQUIRED");
        }
    }

    static String canonical(long userId, String taskNo, long deviceId, String deviceInstanceNo,
                            AppTaskCompleteRequest request) {
        return String.join("\n", String.valueOf(userId), trim(taskNo), String.valueOf(deviceId),
                trim(deviceInstanceNo), trim(request.resultHash()).toLowerCase(Locale.ROOT),
                String.valueOf(request.proofTimestamp()), trim(request.proofNonce()).toLowerCase(Locale.ROOT));
    }

    private static Map<String, Executor> parseExecutors(String source) {
        Map<String, Executor> result = new LinkedHashMap<>();
        for (String item : trim(source).split(",")) {
            String[] parts = item.trim().split(":", 3);
            if (parts.length != 3) continue;
            try {
                byte[] key = Base64.getDecoder().decode(parts[2]);
                if (!EXECUTOR_ID.matcher(parts[0]).matches() || !EXECUTOR_ID.matcher(parts[1]).matches()
                        || key.length < 32) continue;
                result.put(parts[0], new Executor(parts[1], key));
            }
            catch (IllegalArgumentException ignored) { }
        }
        return Map.copyOf(result);
    }

    private static String hmac(byte[] key, String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("TASK_ASSIGNMENT_HMAC_FAILED", ex); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("TASK_ASSIGNMENT_HASH_FAILED", ex); }
    }
    private static boolean constantTime(String left, String right) {
        return MessageDigest.isEqual(trim(left).getBytes(StandardCharsets.UTF_8), trim(right).getBytes(StandardCharsets.UTF_8));
    }
    private static boolean hex64(String value) { return trim(value).matches("(?i)[a-f0-9]{64}"); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String upper(String value) { return trim(value).toUpperCase(Locale.ROOT); }
    private static BizException untrusted(String message) { return new BizException(403, message); }

    private record Executor(String deviceInstanceNo, byte[] key) {}
    public record Verification(boolean sandbox, String proofHash) {}
}
