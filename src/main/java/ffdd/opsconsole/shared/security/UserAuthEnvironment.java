package ffdd.opsconsole.shared.security;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Server-owned audience for user credentials; never derived from a request. */
public enum UserAuthEnvironment {
    SANDBOX,
    PRODUCTION;

    public static final String CLAIM = "authEnvironment";
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "test");

    public static Optional<UserAuthEnvironment> resolve(Environment environment) {
        if (environment == null) return Optional.empty();
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) return Optional.of(PRODUCTION);
        if (profiles.length == 1 && DEVELOPMENT_PROFILES.contains(profiles[0])) return Optional.of(SANDBOX);
        if (profiles.length == 1 && "prod".equals(profiles[0])) return Optional.of(PRODUCTION);
        return Optional.empty();
    }

    public static Optional<UserAuthEnvironment> fromClaim(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean acceptsSandbox(Integer sandbox) {
        return this == SANDBOX ? Integer.valueOf(1).equals(sandbox) : Integer.valueOf(0).equals(sandbox);
    }

    /**
     * The fixed development Passkey account is a local convenience capability,
     * not a network login provider. A direct caller must be loopback and a
     * browser caller must also present a loopback Origin. Origin may be absent
     * for local command-line health and acceptance probes.
     */
    public static boolean isLocalDevelopmentRequest(String clientAddress, String origin) {
        if (!isLoopbackAddress(clientAddress)) return false;
        if (origin == null || origin.isBlank()) return true;
        try {
            URI value = URI.create(origin.trim());
            String scheme = value.getScheme();
            return value.getUserInfo() == null
                    && value.getRawQuery() == null
                    && value.getRawFragment() == null
                    && (value.getRawPath() == null || value.getRawPath().isEmpty())
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && isLoopbackAddress(value.getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Fail closed if a higher-priority environment value re-enables XFF rewriting in dev. */
    public static boolean hasSafeDevelopmentForwardHeaderPolicy(Environment environment) {
        if (environment == null) return false;
        String strategy = environment.getProperty("server.forward-headers-strategy");
        return strategy != null && "none".equalsIgnoreCase(strategy.trim());
    }

    public static boolean isLoopbackAddress(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || normalized.startsWith("127.")
                || normalized.startsWith("::ffff:127.");
    }
}
