package ffdd.opsconsole.content.domain;

/** Server-side experiment counters keyed by the persisted variant identity. */
public record CopyExperimentVariantMetric(
        String name,
        String version,
        long impressions,
        long conversions) {
}
