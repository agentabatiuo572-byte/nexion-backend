package ffdd.opsconsole.risk.dto;

public record RiskRuleStatusRequest(
        String state,
        String reason,
        String operator
) {
}
