package ffdd.opsconsole.content.dto;

import java.util.List;

public record DisclosureDraftRequest(
        String version,
        String jurisdiction,
        String languageScope,
        String effectiveDate,
        Boolean requiresReack,
        String zh,
        String vi,
        String en,
        List<DisclosureChapterInput> chapters,
        Long expectedRevision,
        String expectedContentHash,
        String operator,
        String reason) {

    public DisclosureDraftRequest(String version, String jurisdiction, String languageScope, String effectiveDate,
                                  Boolean requiresReack, String zh, String vi, String en,
                                  List<DisclosureChapterInput> chapters, String operator, String reason) {
        this(version, jurisdiction, languageScope, effectiveDate, requiresReack, zh, vi, en, chapters,
                null, null, operator, reason);
    }

    public DisclosureDraftRequest(String version, String jurisdiction, String languageScope, String effectiveDate,
                                   Boolean requiresReack, String zh, String vi, String en,
                                   String operator, String reason) {
        this(version, jurisdiction, languageScope, effectiveDate, requiresReack, zh, vi, en, List.of(),
                null, null, operator, reason);
    }
}
