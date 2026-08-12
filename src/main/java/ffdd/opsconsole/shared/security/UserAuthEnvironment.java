package ffdd.opsconsole.shared.security;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Server-owned audience for user credentials; never derived from a request. */
public enum UserAuthEnvironment {
    SANDBOX,
    PRODUCTION;

    public static final String CLAIM = "authEnvironment";
    private static final Set<String> SANDBOX_PROFILES = Set.of("acceptance", "test", "local-sandbox");

    public static Optional<UserAuthEnvironment> resolve(Environment environment) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (profiles.length == 1 && SANDBOX_PROFILES.contains(profiles[0])) return Optional.of(SANDBOX);
        if (profiles.length == 0 || (profiles.length == 1
                && ("production".equals(profiles[0]) || "default".equals(profiles[0])))) {
            return Optional.of(PRODUCTION);
        }
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
}
