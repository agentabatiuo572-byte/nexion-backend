package ffdd.opsconsole.risk.domain;

public record RiskRuleDimensionView(
        String ruleKey,
        String ruleId,
        String name,
        String conditionText,
        String conditionDefault,
        String why,
        String action,
        String note,
        String icon
) {
}
