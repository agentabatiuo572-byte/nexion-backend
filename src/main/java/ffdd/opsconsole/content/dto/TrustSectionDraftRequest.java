package ffdd.opsconsole.content.dto;

import java.util.List;

public record TrustSectionDraftRequest(
        String version,
        String description,
        String structure,
        List<TrustSectionFieldInput> fields,
        Long expectedRevision,
        String operator,
        String reason) {}
