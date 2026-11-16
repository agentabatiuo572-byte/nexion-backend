package ffdd.opsconsole.risk.domain;

public record RiskRuleHitView(
        String withdrawalNo,
        String userNo,
        String amountText,
        String amountValue,
        String currency,
        String amountSource,
        String ruleId,
        String dimension,
        String action,
        String reason,
        String timeText,
        String conditionText
) {
}
