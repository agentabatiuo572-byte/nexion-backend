package ffdd.opsconsole.content.domain;

public record DisclosureDraftView(
        String version,
        String jurisdiction,
        String languageScope,
        String effectiveDate,
        boolean requiresReack,
        String zh,
        String vi,
        String en,
        String status) {
}
