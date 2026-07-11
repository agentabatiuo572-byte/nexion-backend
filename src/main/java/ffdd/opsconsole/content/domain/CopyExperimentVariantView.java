package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record CopyExperimentVariantView(
        String name,
        String version,
        int split,
        BigDecimal cvr) {

    public CopyExperimentVariantView(String name, int split, BigDecimal cvr) {
        this(name, null, split, cvr);
    }
}
