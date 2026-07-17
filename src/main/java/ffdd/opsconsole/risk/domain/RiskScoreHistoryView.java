package ffdd.opsconsole.risk.domain;

import java.util.List;

public record RiskScoreHistoryView(
        Long modelVersion,
        Integer modelScore,
        Integer effectiveScore,
        String scoreState,
        List<RiskScoreContributionView> contributions,
        String reason,
        String operator,
        String createdAt
) {
}
