package ffdd.opsconsole.risk.domain;

import java.util.List;

public record RiskScoreUserView(
        String userNo,
        Integer modelScore,
        Integer effectiveScore,
        Boolean overridden,
        String bandLabel,
        String bandTone,
        String modelVersion,
        Long rowVersion,
        String asOf,
        String updatedText,
        List<RiskScoreContributionView> contributions,
        List<RiskScoreHistoryView> history
) {
    public RiskScoreUserView(
            String userNo, Integer modelScore, Integer effectiveScore, Boolean overridden,
            String bandLabel, String bandTone, String modelVersion, Long rowVersion,
            String asOf, String updatedText, List<RiskScoreContributionView> contributions) {
        this(userNo, modelScore, effectiveScore, overridden, bandLabel, bandTone,
                modelVersion, rowVersion, asOf, updatedText, contributions, List.of());
    }

    public RiskScoreUserView(
            String userNo, Integer modelScore, Integer effectiveScore, Boolean overridden,
            String bandLabel, String bandTone, String modelVersion, String updatedText,
            List<RiskScoreContributionView> contributions) {
        this(userNo, modelScore, effectiveScore, overridden, bandLabel, bandTone,
                modelVersion, 0L, updatedText, updatedText, contributions, List.of());
    }
}
