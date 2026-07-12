package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;

public record AppRiskDisclosureView(
        String jurisdiction,
        String jurisdictionName,
        String version,
        String languageScope,
        String effectiveDate,
        boolean acknowledged,
        LocalDateTime acknowledgedAt,
        List<DisclosureChapterView> chapters) {
}
