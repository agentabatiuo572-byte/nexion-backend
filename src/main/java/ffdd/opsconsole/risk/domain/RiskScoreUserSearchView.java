package ffdd.opsconsole.risk.domain;

public record RiskScoreUserSearchView(
        String userNo,
        String label,
        String sub,
        Integer modelScore,
        Integer effectiveScore,
        String bandLabel,
        String bandTone,
        Boolean overridden
) {
}
