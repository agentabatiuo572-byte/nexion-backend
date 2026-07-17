package ffdd.opsconsole.risk.domain;

public record RiskScoreContributionView(
        String dimKey,
        String name,
        Boolean hit,
        String evidence,
        Integer subScore,
        Integer weightPct,
        Integer points
) {
    public RiskScoreContributionView(String name, String evidence, Integer points) {
        this(name, name, points != null && points > 0, evidence, points, 100, points);
    }
}
