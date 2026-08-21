package ffdd.opsconsole.content.terms.domain;

import java.time.LocalDateTime;
import java.util.List;

public record LegalTermsVersionView(
        Long id, String locale, String jurisdiction, String version,
        LocalDateTime effectiveAt, String status, String title, String summary,
        List<LegalTermsSection> sections, long revision,
        LocalDateTime publishedAt, LocalDateTime revokedAt) { }
