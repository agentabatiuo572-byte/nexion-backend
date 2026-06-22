package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record CopyExperimentVariantView(
        String name,
        int split,
        BigDecimal cvr) {
}
