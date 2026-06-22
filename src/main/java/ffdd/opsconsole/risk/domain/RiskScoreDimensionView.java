package ffdd.opsconsole.risk.domain;

public record RiskScoreDimensionView(
        String dimKey,
        String name,
        String source,
        Integer weightPct
) {
}
