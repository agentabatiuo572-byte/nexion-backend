package ffdd.opsconsole.risk.dto;

public record RiskRuleCreateRequest(
        String dimension,
        String conditionText,
        String action,
        String reason,
        String operator
) {
}
