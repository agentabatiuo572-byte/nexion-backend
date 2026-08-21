package ffdd.opsconsole.content.terms.domain;

import java.time.LocalDateTime;
import java.util.List;

public record LegalTermsCurrentView(
        String source, String sourceEnvironment, String runId,
        String requestedLocale, String resolvedLocale,
        String requestedJurisdiction, String resolvedJurisdiction,
        String provenance, String version, LocalDateTime effectiveAt,
        String title, String summary, List<LegalTermsSection> sections,
        boolean acknowledged, LocalDateTime acknowledgedAt) { }
