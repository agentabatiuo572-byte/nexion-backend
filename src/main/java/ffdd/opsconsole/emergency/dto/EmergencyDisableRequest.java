package ffdd.opsconsole.emergency.dto;

import java.util.List;

public record EmergencyDisableRequest(
        List<String> keys,
        String reason,
        String operator,
        String triggerBasis,
        String regulatoryContext,
        String dispositionPlan) {
    public EmergencyDisableRequest(List<String> keys, String reason, String operator) {
        this(keys, reason, operator, null, null, null);
    }
}
