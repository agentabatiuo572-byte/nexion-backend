package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public record AppRiskDisclosureView(
        String jurisdiction,
        String jurisdictionName,
        String version,
        String languageScope,
        String effectiveDate,
        boolean acknowledged,
        LocalDateTime acknowledgedAt,
        List<DisclosureChapterView> chapters,
        String acknowledgmentToken,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        OffsetDateTime acknowledgmentTokenExpiresAt,
        long minimumReadingSeconds) {
}
