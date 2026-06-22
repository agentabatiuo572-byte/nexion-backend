package ffdd.opsconsole.risk.domain;

public record RiskRuleHitView(
        String withdrawalNo,
        String userNo,
        String amountText,
        String ruleId,
        String dimension,
        String action,
        String timeText
) {
}
