package ffdd.opsconsole.risk.facade;

import ffdd.opsconsole.risk.domain.RiskRuleView;
import java.util.List;

/** One deterministic K3 route; matched rules remain available for audit and replay. */
public record WithdrawalRiskDecision(
        String action,
        String primaryRuleId,
        String primaryDimension,
        List<RiskRuleView> matchedRules) {

    public WithdrawalRiskDecision {
        matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
    }

    public boolean held() {
        return !"pass".equals(action);
    }
}
