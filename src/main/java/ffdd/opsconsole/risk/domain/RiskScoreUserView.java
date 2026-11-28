package ffdd.opsconsole.risk.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty("scored")
    public boolean scored() {
        return isScoredModel(modelVersion);
    }

    public static boolean isScoredModel(String modelVersion) {
        return modelVersion != null && !"pending".equals(modelVersion);
    }

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
