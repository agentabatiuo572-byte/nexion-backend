package ffdd.opsconsole.risk.dto;

public record RiskScoringBandRequest(
        Integer lowMax,
        Integer highMin,
        String reason,
        String operator
) {
}
