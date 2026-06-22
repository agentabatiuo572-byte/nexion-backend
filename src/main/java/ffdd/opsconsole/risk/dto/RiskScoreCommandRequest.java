package ffdd.opsconsole.risk.dto;

public record RiskScoreCommandRequest(
        String reason,
        String operator
) {
}
