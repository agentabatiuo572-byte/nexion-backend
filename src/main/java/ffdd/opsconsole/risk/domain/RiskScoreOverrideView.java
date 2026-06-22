package ffdd.opsconsole.risk.domain;

public record RiskScoreOverrideView(
        String userNo,
        Integer modelScore,
        Integer overrideScore,
        String reason,
        String operator,
        String timeText,
        Boolean active
) {
}
