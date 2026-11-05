package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import org.springframework.util.StringUtils;

/** Shared A1 governance definition, also used by the server-only recovery command. */
public final class AdminAccountGovernance {
    public static final int MIN_EFFECTIVE_SUPERS = 2;

    private AdminAccountGovernance() {}

    public static boolean mfaBound(AdminAccountStateEntity state) {
        return state != null
                && Integer.valueOf(1).equals(state.getTfaRequired())
                && StringUtils.hasText(state.getTfaSecretEncrypted())
                && state.getTfaBoundAt() != null;
    }

    public static boolean effectiveSuper(String role, String status, boolean mfaBound) {
        return "super".equals(role) && "enabled".equals(status) && mfaBound;
    }
}
