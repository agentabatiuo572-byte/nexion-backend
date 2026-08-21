package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.dto.UserOAuthSandboxChallengeRequest;
import ffdd.opsconsole.auth.dto.UserOAuthSandboxChallengeResponse;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Issues short-lived, one-time Sandbox OAuth challenges.
 *
 * <p>The browser never chooses the identity subject. This prevents a caller
 * that can reach the local Sandbox API from replaying a predictable subject
 * and taking over another Sandbox wallet/session. The in-memory store is
 * deliberate: this capability exists only in a single-node isolated profile;
 * a restart invalidates outstanding challenges instead of weakening them.</p>
 */
@Service
public class OAuthSandboxChallengeService {
    private static final Set<String> PROVIDERS = Set.of("GOOGLE", "APPLE", "PASSKEY", "TELEGRAM");
    private static final String DEVELOPMENT_PASSKEY_SUBJECT = "development-passkey-fixed-account";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_OUTSTANDING = 10_000;

    private final Environment environment;
    private final Clock clock;
    @SuppressWarnings("ArchitectureConfigField")
    private final int maxOutstanding;
    private final ConcurrentHashMap<String, Challenge> challenges = new ConcurrentHashMap<>();

    @Autowired
    public OAuthSandboxChallengeService(Environment environment) {
        this(environment, Clock.systemUTC(), MAX_OUTSTANDING);
    }

    OAuthSandboxChallengeService(Environment environment, Clock clock, int maxOutstanding) {
        this.environment = environment;
        this.clock = clock;
        this.maxOutstanding = Math.max(1, maxOutstanding);
    }

    public synchronized ApiResult<UserOAuthSandboxChallengeResponse> issue(
            UserOAuthSandboxChallengeRequest request, String clientAddress, String requestOrigin) {
        var audience = UserAuthEnvironment.resolve(environment);
        if (audience.isEmpty() || audience.get() != UserAuthEnvironment.SANDBOX) {
            return ApiResult.fail(503, "OAUTH_SANDBOX_CHALLENGE_FORBIDDEN");
        }
        String provider = normalizeProvider(request == null ? null : request.provider());
        if (!PROVIDERS.contains(provider)) return ApiResult.fail(422, "OAUTH_REQUEST_INVALID");
        if (!UserAuthEnvironment.hasSafeDevelopmentForwardHeaderPolicy(environment)) {
            return ApiResult.fail(503, "OAUTH_DEVELOPMENT_PASSKEY_NETWORK_POLICY_INVALID");
        }
        if (!UserAuthEnvironment.isLocalDevelopmentRequest(clientAddress, requestOrigin)) {
            return ApiResult.fail(403, "OAUTH_DEVELOPMENT_PASSKEY_LOCAL_ONLY");
        }
        long now = clock.millis();
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (challenges.size() >= maxOutstanding) {
            return ApiResult.fail(429, "OAUTH_SANDBOX_CHALLENGE_CAPACITY");
        }
        String challengeNo = "OAUTH-" + UUID.randomUUID().toString().replace("-", "");
        String subject = "PASSKEY".equals(provider)
                ? DEVELOPMENT_PASSKEY_SUBJECT
                : "sandbox-" + UUID.randomUUID();
        challenges.put(challengeNo, new Challenge(provider, subject, now + TTL.toMillis()));
        return ApiResult.ok(new UserOAuthSandboxChallengeResponse(challengeNo, (int) TTL.toSeconds()));
    }

    public Optional<String> consume(String providerValue, String challengeNoValue) {
        String provider = normalizeProvider(providerValue);
        String challengeNo = StringUtils.hasText(challengeNoValue) ? challengeNoValue.trim() : "";
        if (!PROVIDERS.contains(provider) || !challengeNo.matches("OAUTH-[a-f0-9]{32}")) {
            return Optional.empty();
        }
        long now = clock.millis();
        AtomicReference<String> subject = new AtomicReference<>();
        challenges.computeIfPresent(challengeNo, (ignored, challenge) -> {
            if (challenge.expiresAtMillis() <= now) return null;
            if (!challenge.provider().equals(provider)) return challenge;
            subject.set(challenge.subject());
            return null;
        });
        return Optional.ofNullable(subject.get());
    }

    private String normalizeProvider(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private record Challenge(String provider, String subject, long expiresAtMillis) {
    }
}
