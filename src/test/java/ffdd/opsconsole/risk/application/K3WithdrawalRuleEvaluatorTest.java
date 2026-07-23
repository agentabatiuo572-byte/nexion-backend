package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class K3WithdrawalRuleEvaluatorTest {
    private final K3WithdrawalRuleEvaluator evaluator = new K3WithdrawalRuleEvaluator();

    @Test
    void evaluatesAllFourDimensionsAndChoosesStrictestAction() {
        List<RiskRuleView> rules = List.of(
                rule("WR-AMOUNT", "金额", "单笔 >= $100", "delay", 80),
                rule("WR-VELOCITY", "速度", "24h > 3 笔 或 > $5,000", "manual", 90),
                rule("WR-NEW", "新账户", "注册 < 7 天", "delay", 70),
                rule("WR-ADDRESS", "地址信誉", "内部黑名单 + 链上信誉", "freeze", 10));

        var decision = evaluator.evaluate(rules, new WithdrawalRiskContext(
                7L, "WD-FOUR", "U00000007", new BigDecimal("100.00"),
                4, new BigDecimal("5100.00"), 3, "low",
                "USDT-TRC20", "TR7NHqExampleAddress", new BigDecimal("0.30")));

        assertThat(decision.action()).isEqualTo("freeze");
        assertThat(decision.primaryRuleId()).isEqualTo("WR-ADDRESS");
        assertThat(decision.matchedRules()).extracting(RiskRuleView::ruleId)
                .containsExactlyInAnyOrder("WR-AMOUNT", "WR-VELOCITY", "WR-NEW", "WR-ADDRESS");
    }

    @Test
    void honorsStrictOperatorsAndUsesPriorityOnlyAsTieBreaker() {
        List<RiskRuleView> rules = List.of(
                rule("WR-STRICT", "金额", "单笔 > $100", "manual", 100),
                rule("WR-INCLUSIVE-LOW", "金额", "单笔 >= $100", "manual", 10),
                rule("WR-INCLUSIVE-HIGH", "金额", "单笔 >= $100", "manual", 90));

        var decision = evaluator.evaluate(rules, new WithdrawalRiskContext(
                7L, "WD-BOUNDARY", "U00000007", new BigDecimal("100.00"),
                1, new BigDecimal("100.00"), 30, "normal"));

        assertThat(decision.action()).isEqualTo("manual");
        assertThat(decision.primaryRuleId()).isEqualTo("WR-INCLUSIVE-HIGH");
        assertThat(decision.matchedRules()).extracting(RiskRuleView::ruleId)
                .containsExactlyInAnyOrder("WR-INCLUSIVE-LOW", "WR-INCLUSIVE-HIGH");
    }

    @Test
    void ignoresInactiveLegacyRulesAndReturnsImplicitPass() {
        List<RiskRuleView> rules = List.of(
                rule("WR-PAUSED", "金额", "单笔 >= $100", "freeze", 90, "paused"),
                rule("WR-UNKNOWN", "金额", "legacy magic expression", "freeze", 80, "archived"));

        var decision = evaluator.evaluate(rules, new WithdrawalRiskContext(
                7L, "WD-PASS", "U00000007", new BigDecimal("999.00"),
                1, new BigDecimal("999.00"), 30, "normal"));

        assertThat(decision.action()).isEqualTo("pass");
        assertThat(decision.primaryRuleId()).isNull();
        assertThat(decision.matchedRules()).isEmpty();
    }

    @Test
    void failsClosedWhenAnActiveLegacyRuleCannotBeEvaluated() {
        WithdrawalRiskContext context = new WithdrawalRiskContext(
                7L, "WD-INVALID-ACTIVE", "U00000007", new BigDecimal("100.00"),
                1, new BigDecimal("100.00"), 30, "normal");

        assertThatThrownBy(() -> evaluator.evaluate(List.of(
                rule("WR-INVALID", "amount", "legacy magic expression", "freeze", 99)), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_ACTIVE_RULE_INVALID");
        assertThatThrownBy(() -> evaluator.evaluate(null, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_RULE_SOURCE_UNAVAILABLE");
    }

    @Test
    void addressReputationSourceAndThresholdAreServerAuthoritative() {
        WithdrawalRiskContext internalLow = context("low", new BigDecimal("0.90"));
        WithdrawalRiskContext thirdPartyLow = context("normal", new BigDecimal("0.39"));
        WithdrawalRiskContext thresholdBoundary = context("normal", new BigDecimal("0.40"));

        assertThat(evaluator.evaluate(List.of(addressRule(
                "WR-INTERNAL", "addressReputationSource=internal; addressReputationLowThreshold=0.4")), internalLow)
                .action()).isEqualTo("freeze");
        assertThat(evaluator.evaluate(List.of(addressRule(
                "WR-THIRD", "addressReputationSource=third-party; addressReputationLowThreshold=0.4")), thirdPartyLow)
                .action()).isEqualTo("freeze");
        assertThat(evaluator.evaluate(List.of(addressRule(
                "WR-THIRD", "addressReputationSource=third-party; addressReputationLowThreshold=0.4")), thresholdBoundary)
                .action()).isEqualTo("pass");

        assertThat(evaluator.evaluate(List.of(addressRule(
                "WR-COMBINED", "addressReputationSource=combined; addressReputationLowThreshold=0.4")), internalLow)
                .action()).isEqualTo("freeze");
        assertThat(evaluator.evaluate(List.of(addressRule(
                "WR-COMBINED", "addressReputationSource=combined; addressReputationLowThreshold=0.4")), thirdPartyLow)
                .action()).isEqualTo("freeze");
    }

    @Test
    void thirdPartySelectionFailsClosedOnMissingOrMalformedAssessment() {
        RiskRuleView thirdParty = addressRule(
                "WR-THIRD", "addressReputationSource=third-party; addressReputationLowThreshold=0.4");

        assertThatThrownBy(() -> evaluator.evaluate(List.of(thirdParty), context("normal", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_ADDRESS_REPUTATION_UNAVAILABLE");
        assertThatThrownBy(() -> evaluator.evaluate(List.of(thirdParty), context("normal", new BigDecimal("1.01"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_ADDRESS_REPUTATION_INVALID");
        assertThatThrownBy(() -> evaluator.evaluate(List.of(addressRule(
                "WR-BAD-THRESHOLD", "addressReputationSource=third-party; addressReputationLowThreshold=1.01")),
                context("normal", new BigDecimal("0.2"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K3_ACTIVE_RULE_INVALID");
    }

    private WithdrawalRiskContext context(String internalReputation, BigDecimal thirdPartyScore) {
        return new WithdrawalRiskContext(
                7L, "WD-ADDRESS", "U00000007", new BigDecimal("100.00"),
                1, new BigDecimal("100.00"), 30, internalReputation,
                "USDT-TRC20", "TR7NHqExampleAddress", thirdPartyScore);
    }

    private RiskRuleView addressRule(String id, String condition) {
        return rule(id, "地址信誉", condition, "freeze", 90);
    }

    private RiskRuleView rule(String id, String dimension, String condition, String action, int priority) {
        return rule(id, dimension, condition, action, priority, "active");
    }

    private RiskRuleView rule(
            String id, String dimension, String condition, String action, int priority, String state) {
        return new RiskRuleView(
                id, dimension, condition, action, state, false, priority, 0L,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
    }
}
