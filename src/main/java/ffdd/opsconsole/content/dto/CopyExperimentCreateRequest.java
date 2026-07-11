package ffdd.opsconsole.content.dto;

import java.util.List;

public record CopyExperimentCreateRequest(
        String copyKey,
        List<CopyExperimentVariantRequest> variants,
        String note,
        String operator,
        String reason) {
}
