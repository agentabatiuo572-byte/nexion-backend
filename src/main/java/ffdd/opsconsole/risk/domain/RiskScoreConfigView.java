package ffdd.opsconsole.risk.domain;

public record RiskScoreConfigView(
        String inputSource,
        Integer bandLowMax,
        Integer bandHighMin,
        Integer autoEscalateScore
) {
}
