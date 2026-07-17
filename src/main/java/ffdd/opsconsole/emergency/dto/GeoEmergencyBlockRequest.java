package ffdd.opsconsole.emergency.dto;

import java.util.List;

public record GeoEmergencyBlockRequest(List<String> countries, String triggerBasis, String reason, String operator) {
    public GeoEmergencyBlockRequest(List<String> countries, String reason, String operator) {
        this(countries, null, reason, operator);
    }
}
