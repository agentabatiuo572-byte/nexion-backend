package ffdd.opsconsole.content.terms.dto;

import ffdd.opsconsole.content.terms.domain.LegalTermsSection;
import java.time.LocalDateTime;
import java.util.List;

public record LegalTermsDraftRequest(
        String locale, String jurisdiction, String version, LocalDateTime effectiveAt,
        String title, String summary, List<LegalTermsSection> sections,
        Long expectedRevision, String reason) { }
