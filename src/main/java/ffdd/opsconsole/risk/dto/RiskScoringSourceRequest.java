package ffdd.opsconsole.risk.dto;

public record RiskScoringSourceRequest(
        String inputSource,
        String reason,
        String operator
) {
}
