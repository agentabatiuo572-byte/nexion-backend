package ffdd.opsconsole.content.domain;

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
        long blocked) {
}
