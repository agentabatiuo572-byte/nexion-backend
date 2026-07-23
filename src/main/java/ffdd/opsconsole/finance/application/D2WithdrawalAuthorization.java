package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.dto.WithdrawalBatchReviewRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("d2WithdrawalAuthorization")
public class D2WithdrawalAuthorization {

    public boolean canReview(Authentication authentication, WithdrawalReviewRequest request) {
        return request != null && has(authentication, permission(request.action()));
    }

    public boolean canBatch(Authentication authentication, WithdrawalBatchReviewRequest request) {
        return request != null
                && has(authentication, "finance_d2_withdrawal_batch")
                && has(authentication, permission(request.action()));
    }

    private String permission(String action) {
        return switch (action == null ? "" : action.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> "finance_d2_withdrawal_approve";
            case "DELAY" -> "finance_d2_withdrawal_delay";
            case "FREEZE" -> "finance_d2_withdrawal_freeze";
            case "UNFREEZE" -> "finance_d2_withdrawal_unfreeze";
            case "REJECT" -> "finance_d2_withdrawal_reject";
            case "REFUND" -> "finance_d2_withdrawal_refund";
            default -> "D2_ACTION_INVALID";
        };
    }

    private boolean has(Authentication authentication, String authority) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
