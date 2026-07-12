package ffdd.opsconsole.content.domain;

import java.util.List;

public record TrustSectionVersionView(
        String sectionKey,
        String version,
        String description,
        String structure,
        List<Field> fields,
        String status,
        long revision,
        String operator,
        String updatedAt) {
    public record Field(String key, String label, String value) {}
}
