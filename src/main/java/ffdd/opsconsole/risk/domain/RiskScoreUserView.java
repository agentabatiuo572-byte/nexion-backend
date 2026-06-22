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
        String updatedText,
        List<RiskScoreContributionView> contributions
) {
}
