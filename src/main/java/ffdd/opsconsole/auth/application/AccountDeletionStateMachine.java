package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** Small, side-effect-free guard for the account-closure lifecycle. */
final class AccountDeletionStateMachine {
    private AccountDeletionStateMachine() {
    }

    static boolean canTransition(String from, String to) {
        if (!isState(from) || !isState(to)) return false;
        return switch (from.trim().toUpperCase(Locale.ROOT)) {
            case "REQUESTED" -> "IN_REVIEW".equals(to) || "BLOCKED".equals(to) || "CANCELLED".equals(to);
            case "IN_REVIEW" -> "BLOCKED".equals(to) || "COMPLETED".equals(to) || "CANCELLED".equals(to);
            case "BLOCKED" -> "IN_REVIEW".equals(to) || "CANCELLED".equals(to);
            default -> false;
        };
    }

    static void requireTransition(String from, String to, String reason) {
        if (!isState(from) || !isState(to)) {
            throw new BizException(422, "ACCOUNT_DELETION_INVALID_STATE");
        }
        if (!canTransition(from, to)) {
            throw new BizException(409, "ACCOUNT_DELETION_INVALID_STATE_TRANSITION");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException(422, "ACCOUNT_DELETION_REASON_REQUIRED");
        }
    }

    private static boolean isState(String state) {
        if (!StringUtils.hasText(state)) return false;
        return switch (state.trim().toUpperCase(Locale.ROOT)) {
            case "REQUESTED", "IN_REVIEW", "BLOCKED", "COMPLETED", "CANCELLED" -> true;
            default -> false;
        };
    }
}
