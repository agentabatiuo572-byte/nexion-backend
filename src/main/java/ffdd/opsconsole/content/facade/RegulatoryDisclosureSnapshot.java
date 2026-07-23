package ffdd.opsconsole.content.facade;

import java.util.List;

/** Current I5 disclosure truth exposed to regulatory-report consumers. */
public record RegulatoryDisclosureSnapshot(
        String jurisdictionCode,
        String jurisdictionName,
        List<String> countryCodes,
        String disclosureVersion,
        String disclosureStatus,
        String contentHash,
        int chapterCount,
        String publishedAt,
        long affectedUsers,
        double acknowledgementProgress,
        long blockedUsers) {
}

