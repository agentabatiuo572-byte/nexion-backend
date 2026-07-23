package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("d5WithdrawalAuthorization")
public class D5WithdrawalAuthorization {

    public boolean canUpdate(Authentication authentication, WithdrawalParamUpdateRequest request) {
        return request != null && has(authentication, permission(request.key()));
    }

    public boolean canUpdateLimits(Authentication authentication, WithdrawalLimitsUpdateRequest request) {
        if (request == null) {
            return false;
        }
        // A caller who can read D5 must reach the service-level 422 read-only error for Phase fields.
        if (request.hasPhaseFields()) {
            return has(authentication, "finance_d5_read");
        }
        var fields = request.changedD5Fields();
        return !fields.isEmpty() && fields.stream().allMatch(field -> has(authentication, permission(field)));
    }

    private String permission(String key) {
        return switch (key == null ? "" : key.trim()) {
            case "dailyLimitCount" -> "finance_d5_daily_limit_write";
            case "balanceMaxRatio" -> "finance_d5_balance_max_write";
            case "networkFee", "networkFeeRatio", "networkFeeMin", "networkFeeMax", "nexFeeOffsetRate" -> "finance_d5_fee_write";
            default -> "D5_PARAM_INVALID";
        };
    }

    private boolean has(Authentication authentication, String authority) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
