package ffdd.opsconsole.risk.dto;

public record RiskRuleConditionRequest(
        String conditionText,
        String reason,
        String operator
) {
}
