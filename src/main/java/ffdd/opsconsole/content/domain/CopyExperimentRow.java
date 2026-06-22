package ffdd.opsconsole.content.domain;

import java.util.List;

public record CopyExperimentRow(
        String id,
        String copyKey,
        List<CopyExperimentVariantView> variants,
        String audience,
        String impressions,
        String conversions,
        String state,
        String note) {
}
