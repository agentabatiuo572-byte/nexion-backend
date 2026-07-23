package ffdd.opsconsole.bi.dto;

public record B3FunnelViewRequest(
        String name,
        String cohort,
        String phase,
        String ref,
        String granularity,
        String comparison) {
}
