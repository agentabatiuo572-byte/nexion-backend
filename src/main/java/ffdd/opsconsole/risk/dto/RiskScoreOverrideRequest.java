package ffdd.opsconsole.risk.dto;

public record RiskScoreOverrideRequest(
        Integer score,
        String reason,
        String operator
) {
}
