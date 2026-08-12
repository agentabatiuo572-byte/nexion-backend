package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import org.springframework.core.env.Environment;

/**
 * Server-owned account/environment admission. The client never selects this
 * boundary: the active server profile and the persisted nx_user.sandbox bit
 * must agree before authentication can issue or rotate any session.
 */
final class UserAuthEnvironmentPolicy {
    enum Decision {
        ALLOW,
        ACCOUNT_MISMATCH,
        PROFILE_FORBIDDEN
    }

    private UserAuthEnvironmentPolicy() {
    }

    static Decision evaluate(Environment environment, UserEntity user) {
        if (user == null) return Decision.ACCOUNT_MISMATCH;
        return UserAuthEnvironment.resolve(environment)
                .map(audience -> audience.acceptsSandbox(user.getSandbox()) ? Decision.ALLOW : Decision.ACCOUNT_MISMATCH)
                .orElse(Decision.PROFILE_FORBIDDEN);
    }
}
