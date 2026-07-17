package ffdd.opsconsole.risk.dto;

public record RiskRuleConditionRequest(
        String conditionText,
        String action,
        Integer priority,
        Long expectedVersion,
        String reason,
        String operator
) {
    public RiskRuleConditionRequest(String conditionText, String reason, String operator) {
        this(conditionText, null, null, 0L, reason, operator);
    }
}
