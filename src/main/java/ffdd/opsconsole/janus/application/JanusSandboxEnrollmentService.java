package ffdd.opsconsole.janus.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Issues short-lived, server-owned credentials for the isolated Janus sandbox. */
@Service
public class JanusSandboxEnrollmentService {
    private static final Set<String> SANDBOX_PROFILES = Set.of("dev", "test");
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9._:-]{3,128}$");

    private final Environment environment;
    @SuppressWarnings("ArchitectureConfigField")
    private final String mode;
    @SuppressWarnings("ArchitectureConfigField")
    private final String sandboxTargets;
    @SuppressWarnings("ArchitectureConfigField")
    private final long ttlMs;
    private final LongSupplier now;
    private final SecureRandom random;
    private final ConcurrentHashMap<String, Enrollment> enrollments = new ConcurrentHashMap<>();

    @Autowired
    public JanusSandboxEnrollmentService(Environment environment,
            @Value("${nexion.janus.executor.mode:PRODUCTION}") String mode,
            @Value("${nexion.janus.executor.sandbox-targets:}") String sandboxTargets,
            @Value("${nexion.janus.executor.sandbox-enrollment-ttl-ms:900000}") long ttlMs) {
        this(environment, mode, sandboxTargets, ttlMs, System::currentTimeMillis, new SecureRandom());
    }

    JanusSandboxEnrollmentService(Environment environment, String mode, String sandboxTargets, long ttlMs,
                                  LongSupplier now, SecureRandom random) {
        this.environment = environment;
        this.mode = mode;
        this.sandboxTargets = sandboxTargets;
        this.ttlMs = Math.max(60_000L, ttlMs);
        this.now = now;
        this.random = random;
    }

    public Issue issue(long userId, String deviceId) {
        requireSandbox();
        String normalizedDeviceId = clean(deviceId);
        if (userId <= 0 || !DEVICE_ID.matcher(normalizedDeviceId).matches()) {
            throw new IllegalArgumentException("JANUS_SANDBOX_ENROLLMENT_INVALID");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = Math.addExact(now.getAsLong(), ttlMs);
        Enrollment enrollment = new Enrollment(digest(token), expiresAt);
        enrollments.put(key(userId, normalizedDeviceId), enrollment);
        pruneExpired();
        return new Issue(String.valueOf(userId), normalizedDeviceId, token, expiresAt, targets());
    }

    public boolean verify(long userId, String deviceId, String token) {
        if (!sandboxEnabled() || userId <= 0 || !DEVICE_ID.matcher(clean(deviceId)).matches() || clean(token).isEmpty()) {
            return false;
        }
        Enrollment enrollment = enrollments.get(key(userId, clean(deviceId)));
        if (enrollment == null || enrollment.expiresAt() <= now.getAsLong()) {
            if (enrollment != null) enrollments.remove(key(userId, clean(deviceId)), enrollment);
            return false;
        }
        return MessageDigest.isEqual(enrollment.tokenDigest(), digest(token));
    }

    public byte[] commandAuthorizationKey(long userId, String deviceId) {
        Enrollment enrollment = enrollments.get(key(userId, clean(deviceId)));
        if (enrollment == null || enrollment.expiresAt() <= now.getAsLong()) return new byte[0];
        return enrollment.tokenDigest().clone();
    }

    public boolean allowsTarget(String target) {
        String value = clean(target);
        return "none".equals(value) || targets().contains(value);
    }

    private void requireSandbox() {
        if (!sandboxEnabled()) throw new IllegalStateException("JANUS_SANDBOX_ENROLLMENT_FORBIDDEN");
    }

    private boolean sandboxEnabled() {
        Set<String> active = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        return "SANDBOX".equalsIgnoreCase(clean(mode)) && active.size() == 1 && SANDBOX_PROFILES.containsAll(active);
    }

    private Set<String> targets() {
        return Arrays.stream(clean(sandboxTargets).split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void pruneExpired() {
        long current = now.getAsLong();
        enrollments.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= current);
    }

    private static String key(long userId, String deviceId) { return userId + "\n" + deviceId; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("JANUS_SANDBOX_ENROLLMENT_DIGEST_FAILED", exception);
        }
    }

    private record Enrollment(byte[] tokenDigest, long expiresAt) { }
    public record Issue(String subjectId, String deviceId, String token, long expiresAt, Set<String> allowedTargets) { }
}
