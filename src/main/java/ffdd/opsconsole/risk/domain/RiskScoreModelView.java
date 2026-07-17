package ffdd.opsconsole.risk.domain;

import java.util.Map;

/** Immutable, versioned K4 model snapshot. */
public record RiskScoreModelView(
        Long version,
        Long rowVersion,
        String state,
        Map<String, Integer> weights,
        Map<String, Boolean> inputSources,
        Map<String, Integer> scoreMappings,
        Integer bandLowMax,
        Integer bandHighMin,
        Integer autoEscalateScore,
        String reason,
        String createdBy,
        String publishedBy,
        String createdAt,
        String publishedAt
) {
    public RiskScoreModelView(
            Long version, Long rowVersion, String state,
            Map<String, Integer> weights, Map<String, Boolean> inputSources,
            Integer bandLowMax, Integer bandHighMin, Integer autoEscalateScore,
            String reason, String createdBy, String publishedBy, String createdAt, String publishedAt) {
        this(version, rowVersion, state, weights, inputSources,
                ffdd.opsconsole.risk.application.K4RiskScorer.DEFAULT_MAPPINGS,
                bandLowMax, bandHighMin, autoEscalateScore,
                reason, createdBy, publishedBy, createdAt, publishedAt);
    }
}
