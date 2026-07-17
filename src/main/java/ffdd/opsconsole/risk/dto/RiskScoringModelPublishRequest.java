package ffdd.opsconsole.risk.dto;

public record RiskScoringModelPublishRequest(
        Long expectedVersion,
        String reason,
        String operator
) {
}
