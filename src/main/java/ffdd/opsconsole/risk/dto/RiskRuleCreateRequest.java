package ffdd.opsconsole.risk.dto;

public record RiskRuleCreateRequest(
        String dimension,
        String conditionText,
        String action,
        Integer priority,
        String reason,
        String operator
) {
    public RiskRuleCreateRequest(
            String dimension, String conditionText, String action,
            String reason, String operator) {
        this(dimension, conditionText, action, 50, reason, operator);
    }
}
