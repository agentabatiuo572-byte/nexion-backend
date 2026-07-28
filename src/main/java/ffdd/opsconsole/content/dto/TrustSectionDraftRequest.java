package ffdd.opsconsole.content.dto;

import java.util.List;

public record TrustSectionDraftRequest(
        String version,
        String description,
        String structure,
        List<TrustSectionFieldInput> fields,
        Long expectedRevision,
        String expectedSectionVersion,
        String expectedSectionStatus,
        String operator,
        String reason) {
    public TrustSectionDraftRequest(
            String version,
            String description,
            String structure,
            List<TrustSectionFieldInput> fields,
            Long expectedRevision,
            String operator,
            String reason) {
        this(version, description, structure, fields, expectedRevision, null, null, operator, reason);
    }
}
