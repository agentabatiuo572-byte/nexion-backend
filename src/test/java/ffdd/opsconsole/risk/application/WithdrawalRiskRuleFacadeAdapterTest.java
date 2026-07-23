package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalRiskRuleFacadeAdapterTest {
    private final RiskOpsRepository repository = mock(RiskOpsRepository.class);
    private final ChainAddressReputationGateway addressReputationGateway = mock(ChainAddressReputationGateway.class);
    private final WithdrawalRiskRuleFacadeAdapter facade = new WithdrawalRiskRuleFacadeAdapter(
            repository, new K3WithdrawalRuleEvaluator(), addressReputationGateway);

    @Test
    void recordsEveryHitAndOneB5VisibleDecisionFromTheSameFinalRoute() {
        RiskRuleView delay = rule("WR-DELAY", "金额", "单笔 >= $100", "delay", 90);
        RiskRuleView freeze = rule("WR-FREEZE", "地址信誉", "内部黑名单", "freeze", 10);
        when(repository.withdrawRules()).thenReturn(List.of(delay, freeze));
        WithdrawalRiskContext context = new WithdrawalRiskContext(
                7L, "WD-ONE", "U00000007", new BigDecimal("100"),
                1, new BigDecimal("100"), 30, "low");

        var decision = facade.evaluate(context);
        facade.recordDecision(context, decision);

        assertThat(decision.action()).isEqualTo("freeze");
        assertThat(decision.primaryRuleId()).isEqualTo("WR-FREEZE");
        verify(repository).recordWithdrawRuleHit("WD-ONE", "U00000007", new BigDecimal("100"), delay);
        verify(repository).recordWithdrawRuleHit("WD-ONE", "U00000007", new BigDecimal("100"), freeze);
        verify(repository).recordWithdrawRuleDecision(context, decision);
    }

    @Test
    void callsTheConfiguredProviderOnlyForThirdPartyOrCombinedRules() {
        WithdrawalRiskContext context = new WithdrawalRiskContext(
                7L, "WD-SOURCE", "U00000007", new BigDecimal("100"),
                1, new BigDecimal("100"), 30, "normal",
                "USDT-TRC20", "TR7NHqExampleAddress", null);
        when(addressReputationGateway.score("USDT-TRC20", "TR7NHqExampleAddress"))
                .thenReturn(new BigDecimal("0.39"));

        RiskRuleView internal = rule("WR-INTERNAL", "地址信誉",
                "addressReputationSource=internal; addressReputationLowThreshold=0.4", "freeze", 90);
        when(repository.withdrawRules()).thenReturn(List.of(internal));
        assertThat(facade.evaluate(context).action()).isEqualTo("pass");
        verify(addressReputationGateway, never()).score("USDT-TRC20", "TR7NHqExampleAddress");

        RiskRuleView thirdParty = rule("WR-THIRD", "地址信誉",
                "addressReputationSource=third-party; addressReputationLowThreshold=0.4", "freeze", 90);
        when(repository.withdrawRules()).thenReturn(List.of(thirdParty));
        assertThat(facade.evaluate(context).action()).isEqualTo("freeze");
        verify(addressReputationGateway).score("USDT-TRC20", "TR7NHqExampleAddress");
    }

    private RiskRuleView rule(String id, String dimension, String condition, String action, int priority) {
        return new RiskRuleView(
                id, dimension, condition, action, "active", false, priority, 0L,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
    }
}
