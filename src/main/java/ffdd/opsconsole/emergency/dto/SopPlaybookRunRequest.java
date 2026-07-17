package ffdd.opsconsole.emergency.dto;

import java.util.List;

public record SopPlaybookRunRequest(
        Boolean emergency,
        String reason,
        String operator,
        String triggerBasis,
        String triggerContext,
        List<SopStepConfirmationRequest> stepConfirmations) {

    public SopPlaybookRunRequest(Boolean emergency, String reason, String operator) {
        this(emergency, reason, operator, null, null, List.of());
    }
}
