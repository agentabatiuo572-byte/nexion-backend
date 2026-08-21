package ffdd.opsconsole;

import org.springframework.core.env.ConfigurableEnvironment;

/** One strict parser shared by every early-boot environment decision. */
final class RuntimeProfile {
    static final String DEV = "dev";
    static final String PROD = "prod";
    private static final String ERROR =
            "RUNTIME_PROFILE_FORBIDDEN: exactly one of dev or prod is required";

    private RuntimeProfile() {}

    static String requireSingle(ConfigurableEnvironment environment) {
        String configured = environment.getProperty("spring.profiles.active");
        String configuredProfile = null;
        if (configured != null) {
            configuredProfile = requireSingle(configured.split(",", -1));
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0) {
            if (configuredProfile != null) return configuredProfile;
            throw new IllegalStateException(ERROR);
        }
        String activeProfile = requireSingle(activeProfiles);
        if (configuredProfile != null && !configuredProfile.equals(activeProfile)) {
            throw new IllegalStateException(ERROR);
        }
        return activeProfile;
    }

    private static String requireSingle(String[] profiles) {
        if (profiles.length != 1) {
            throw new IllegalStateException(ERROR);
        }
        String profile = profiles[0].trim();
        if (!DEV.equals(profile) && !PROD.equals(profile)) {
            throw new IllegalStateException(ERROR);
        }
        return profile;
    }
}
