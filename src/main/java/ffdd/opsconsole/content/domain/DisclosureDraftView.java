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
        String status,
        long revision,
        String contentHash) {

    public DisclosureDraftView(String version, String jurisdiction, String languageScope, String effectiveDate,
                               boolean requiresReack, String zh, String vi, String en, String status) {
        this(version, jurisdiction, languageScope, effectiveDate, requiresReack, zh, vi, en, status, 1L, "");
    }
}
