package ffdd.opsconsole.content.domain;

import java.util.List;

public record AppTrustSectionsView(List<Section> sections) {
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
