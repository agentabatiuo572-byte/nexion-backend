package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record CopyExperimentVariantView(
        String name,
        String version,
        int split,
        BigDecimal cvr,
        long impressions,
        long conversions) {

    public CopyExperimentVariantView(String name, String version, int split, BigDecimal cvr) {
        this(name, version, split, cvr, 0L, 0L);
    }

    public CopyExperimentVariantView(String name, int split, BigDecimal cvr) {
        this(name, null, split, cvr, 0L, 0L);
    }
}
