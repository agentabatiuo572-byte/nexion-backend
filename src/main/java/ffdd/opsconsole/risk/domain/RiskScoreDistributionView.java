package ffdd.opsconsole.risk.domain;

public record RiskScoreDistributionView(
        String band,
        String rangeText,
        Long count,
        Double percentage,
        String color,
        String tone
) {
}
