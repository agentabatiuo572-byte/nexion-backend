package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import ffdd.opsconsole.risk.facade.WithdrawalRiskRuleFacade;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WithdrawalRiskRuleFacadeAdapter implements WithdrawalRiskRuleFacade {
    private final RiskOpsRepository riskRepository;
    private final K3WithdrawalRuleEvaluator evaluator;
    private final ChainAddressReputationGateway addressReputationGateway;

    @Override
    public WithdrawalRiskDecision evaluate(WithdrawalRiskContext context) {
        List<RiskRuleView> rules = riskRepository.withdrawRules();
        WithdrawalRiskContext resolved = context;
        if (evaluator.requiresThirdParty(rules)) {
            if (context == null) {
                throw new IllegalStateException("K3_WITHDRAWAL_CONTEXT_UNAVAILABLE");
            }
            resolved = context.withThirdPartyAddressReputationScore(
                    addressReputationGateway.score(context.chain(), context.targetAddress()));
        }
        return evaluator.evaluate(rules, resolved);
    }

    @Override
    @Transactional
    public void recordDecision(WithdrawalRiskContext context, WithdrawalRiskDecision decision) {
        if (context == null || decision == null || !decision.held()) return;
        List<RiskRuleView> matches = decision.matchedRules();
        for (RiskRuleView rule : matches) {
            riskRepository.recordWithdrawRuleHit(
                    context.withdrawalNo(), context.userNo(), context.amountUsdt(), rule);
        }
        riskRepository.recordWithdrawRuleDecision(context, decision);
    }
}
