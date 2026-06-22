package ffdd.opsconsole.risk.domain;

public record RiskScoreContributionView(
        String name,
        String evidence,
        Integer points
) {
}
