package ffdd.opsconsole.risk.dto;

public record RiskScoringEscalateRequest(
        Integer score,
        String reason,
        String operator
) {
}
