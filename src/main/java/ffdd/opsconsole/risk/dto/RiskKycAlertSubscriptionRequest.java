package ffdd.opsconsole.risk.dto;

import java.util.List;

public record RiskKycAlertSubscriptionRequest(
        List<String> alertTypes,
        List<String> channels,
        Long expectedVersion,
        String reason,
        String operator
) {
}
