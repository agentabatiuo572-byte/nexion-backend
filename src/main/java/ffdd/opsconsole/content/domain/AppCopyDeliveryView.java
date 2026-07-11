package ffdd.opsconsole.content.domain;

/** User-facing, server-selected copy body. Experiment fields are null for the published fallback. */
public record AppCopyDeliveryView(
        String copyKey,
        String version,
        String zh,
        String en,
        String vi,
        String experimentId,
        String variant) {
}
