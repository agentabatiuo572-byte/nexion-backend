package ffdd.opsconsole.content.domain;

import java.util.Map;

import java.util.List;

public record DisclosureVersionItem(
        String jurisdiction,
        String version,
        String status,
        String languageScope,
        String effectiveDate,
        boolean requiresReack,
        String zh,
        String vi,
        String en,
        List<DisclosureChapterView> chapters,
        long revision,
        String contentHash,
        long affected,
        double ackProgress,
        long blocked,
        Map<String, String> translations) {
    public DisclosureVersionItem(
        String jurisdiction,
        String version,
        String status,
        String languageScope,
        String effectiveDate,
        boolean requiresReack,
        String zh,
        String vi,
        String en,
        List<DisclosureChapterView> chapters,
        long revision,
        String contentHash,
        long affected,
        double ackProgress,
        long blocked) {
        this(jurisdiction, version, status, languageScope, effectiveDate, requiresReack, zh, vi, en, chapters, revision, contentHash, affected, ackProgress, blocked, Map.of());
    }
}
