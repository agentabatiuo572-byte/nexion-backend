package ffdd.opsconsole.risk.dto;

public record RiskScoreOverrideRequest(
        Integer score,
        Long expectedVersion,
        String reason,
        String operator
) {
    public RiskScoreOverrideRequest(Integer score, String reason, String operator) {
        this(score, null, reason, operator);
    }
}
