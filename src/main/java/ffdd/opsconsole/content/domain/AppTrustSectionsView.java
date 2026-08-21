package ffdd.opsconsole.content.domain;

import java.util.List;

public record AppTrustSectionsView(
        boolean serverCanonical,
        String source,
        String sourceEnvironment,
        String runId,
        List<Section> sections) {
    public AppTrustSectionsView(List<Section> sections) {
        this(true, "nx_trust_section_version:published", "PRODUCTION", "", sections);
    }
    public record Section(
            String sectionKey,
            String version,
            String description,
            String structure,
            List<Field> fields) {
    }

    public record Field(String key, String label, String value) {
    }
}
