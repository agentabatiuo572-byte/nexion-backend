package ffdd.opsconsole.janus.application;

import ffdd.opsconsole.janus.mapper.JanusExecutorClaimNonceMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Authenticates the native Janus command claimant independently of the user JWT. */
@Component
@RequiredArgsConstructor
public class JanusExecutorClaimVerifier {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{3,128}$");
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9._:-]{32,128}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final Pattern PATH = Pattern.compile("^/api/app/janus/[A-Za-z0-9/_?=&.:%-]+$");
    private static final Set<String> SANDBOX_PROFILES = Set.of("dev", "test");

    private final JanusExecutorClaimNonceMapper mapper;
    private final Environment environment;
    @Value("${nexion.janus.executor.mode:PRODUCTION}")
    private final String mode;
    @Value("${nexion.janus.executor.production-keys:}")
    private final String productionKeys;
    @Value("${nexion.janus.executor.max-skew-ms:120000}")
    private final long maxSkewMs;
    private final Optional<JanusSandboxEnrollmentService> sandboxEnrollmentService;

    public Verification verify(long userId, Claim claim) {
        if (userId <= 0 || claim == null || !ID.matcher(trim(claim.deviceId())).matches()
                || !ID.matcher(trim(claim.executorId())).matches() || !NONCE.matcher(trim(claim.nonce())).matches()
                || !Set.of("GET","POST").contains(trim(claim.method()).toUpperCase(java.util.Locale.ROOT))
                || !PATH.matcher(trim(claim.path())).matches() || !SIGNATURE.matcher(trim(claim.bodyDigest())).matches()
                || claim.timestamp() == null || trim(claim.signature()).isEmpty() || trim(claim.signature()).length() > 512) {
            return rejected("JANUS_EXECUTOR_CLAIM_INVALID");
        }
        if (Math.abs(System.currentTimeMillis() - claim.timestamp()) > Math.max(1_000L, maxSkewMs)) {
            return rejected("JANUS_EXECUTOR_CLAIM_EXPIRED");
        }
        String canonical = canonical(userId, claim);
        String normalizedMode = trim(mode).toUpperCase(java.util.Locale.ROOT);
        if ("SANDBOX".equals(normalizedMode)) {
            Set<String> active = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
            boolean enrolled = sandboxEnrollmentService
                    .map(service -> service.verify(userId, claim.deviceId(), claim.signature()))
                    .orElse(false);
            if (active.size() != 1 || !SANDBOX_PROFILES.containsAll(active)
                    || !"sandbox".equals(claim.executorId())
                    || !enrolled) {
                return rejected("JANUS_SANDBOX_CLAIM_ISOLATION_MISMATCH");
            }
        } else if ("PRODUCTION".equals(normalizedMode)) {
            ProductionExecutor executor = parseExecutors(productionKeys).get(claim.executorId());
            if (executor == null) return rejected("JANUS_EXECUTOR_NOT_TRUSTED");
            if (!executor.deviceId().equals(claim.deviceId())) {
                return rejected("JANUS_EXECUTOR_DEVICE_BINDING_MISMATCH");
            }
            if (!SIGNATURE.matcher(trim(claim.signature())).matches()
                    || !constantTime(hmac(executor.key(), canonical), claim.signature().toLowerCase())) {
                return rejected("JANUS_EXECUTOR_CLAIM_SIGNATURE_INVALID");
            }
        } else {
            return rejected("JANUS_EXECUTOR_MODE_INVALID");
        }
        mapper.deleteExpired(System.currentTimeMillis() - 86_400_000L);
        String claimHash = sha256(canonical + "\n" + trim(claim.signature()));
        String prior = mapper.findClaimHash(claim.executorId(), claim.nonce());
        if (prior != null) return constantTime(prior, claimHash)
                ? new Verification(true, true, null)
                : rejected("JANUS_EXECUTOR_CLAIM_REPLAYED");
        int inserted = mapper.claim(claim.executorId(), claim.nonce(), claimHash, claim.deviceId(), claim.timestamp());
        if (inserted == 1) {
            return constantTime(claimHash, mapper.findClaimHash(claim.executorId(), claim.nonce()))
                    ? new Verification(true, false, null)
                    : rejected("JANUS_EXECUTOR_CLAIM_READBACK_FAILED");
        }
        prior = mapper.findClaimHash(claim.executorId(), claim.nonce());
        return prior != null && constantTime(prior, claimHash)
                ? new Verification(true, true, null)
                : rejected("JANUS_EXECUTOR_CLAIM_REPLAYED");
    }

    /** Creates a device-bound authorization for one immutable leased command handle. */
    public String authorizeCommand(String executorId, String deviceId, String commandDigest) {
        return authorizeCommand(0L, executorId, deviceId, commandDigest);
    }

    public String authorizeCommand(long userId, String executorId, String deviceId, String commandDigest) {
        if (!SIGNATURE.matcher(trim(commandDigest)).matches()) {
            throw new IllegalStateException("JANUS_COMMAND_DIGEST_INVALID");
        }
        String normalizedMode = trim(mode).toUpperCase(java.util.Locale.ROOT);
        String canonical = String.join("\n", "JANUS_COMMAND_AUTH_V1", trim(deviceId), trim(executorId),
                trim(commandDigest).toLowerCase(java.util.Locale.ROOT));
        if ("PRODUCTION".equals(normalizedMode)) {
            ProductionExecutor executor = parseExecutors(productionKeys).get(trim(executorId));
            if (executor == null || !executor.deviceId().equals(trim(deviceId))) {
                throw new IllegalStateException("JANUS_COMMAND_EXECUTOR_NOT_TRUSTED");
            }
            return hmac(executor.key(), canonical);
        }
        if ("SANDBOX".equals(normalizedMode)) {
            byte[] enrolledKey = sandboxEnrollmentService
                    .map(service -> service.commandAuthorizationKey(userId, deviceId))
                    .orElseGet(() -> new byte[0]);
            if (enrolledKey.length > 0) return hmac(enrolledKey, canonical);
        }
        throw new IllegalStateException("JANUS_COMMAND_AUTHORIZATION_UNAVAILABLE");
    }

    static String canonical(long userId, Claim claim) {
        return String.join("\n", "JANUS_HTTP_REQUEST_V1", trim(claim.method()).toUpperCase(java.util.Locale.ROOT),
                trim(claim.path()), trim(claim.bodyDigest()), String.valueOf(userId), trim(claim.deviceId()), trim(claim.executorId()),
                trim(claim.nonce()), String.valueOf(claim.timestamp()));
    }

    private static Map<String, ProductionExecutor> parseExecutors(String value) {
        Map<String, ProductionExecutor> result = new LinkedHashMap<>();
        for (String item : value.split(",")) {
            String[] bits = item.trim().split(":", 3);
            if (bits.length != 3) continue;
            try {
                byte[] key = Base64.getDecoder().decode(bits[2]);
                if (!ID.matcher(bits[0]).matches() || !ID.matcher(bits[1]).matches() || key.length < 32) continue;
                result.put(bits[0], new ProductionExecutor(bits[1], key));
            } catch (IllegalArgumentException ignored) {
                // Invalid entries are not trusted.
            }
        }
        return Map.copyOf(result);
    }

    private static String hmac(byte[] key, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JANUS_CLAIM_HMAC_FAILED", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JANUS_CLAIM_HASH_FAILED", exception);
        }
    }

    private static boolean constantTime(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static Verification rejected(String error) {
        return new Verification(false, false, error);
    }

    private record ProductionExecutor(String deviceId, byte[] key) { }

    public record Claim(String deviceId, String executorId, String nonce, Long timestamp, String signature,
                        String method, String path, String bodyDigest) { }

    public record Verification(boolean accepted, boolean duplicate, String error) { }
}
