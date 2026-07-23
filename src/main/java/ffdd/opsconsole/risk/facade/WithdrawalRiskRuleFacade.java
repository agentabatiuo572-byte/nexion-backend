package ffdd.opsconsole.risk.facade;

public interface WithdrawalRiskRuleFacade {
    WithdrawalRiskDecision evaluate(WithdrawalRiskContext context);

    void recordDecision(WithdrawalRiskContext context, WithdrawalRiskDecision decision);
}
