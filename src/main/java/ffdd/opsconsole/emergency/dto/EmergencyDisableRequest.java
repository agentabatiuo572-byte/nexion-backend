package ffdd.opsconsole.emergency.dto;

import java.util.List;

public record EmergencyDisableRequest(
        List<String> keys,
        String reason,
        String operator) {
}
