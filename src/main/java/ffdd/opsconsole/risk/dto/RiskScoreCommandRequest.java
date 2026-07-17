package ffdd.opsconsole.risk.dto;

public record RiskScoreCommandRequest(
        Long expectedVersion,
        String reason,
        String operator
) {
    public RiskScoreCommandRequest(String reason, String operator) {
        this(null, reason, operator);
    }
}
