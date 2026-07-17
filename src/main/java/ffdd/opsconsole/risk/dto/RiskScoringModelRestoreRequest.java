package ffdd.opsconsole.risk.dto;

public record RiskScoringModelRestoreRequest(
        Long modelVersion,
        Long expectedVersion,
        String reason,
        String operator
) {
}
