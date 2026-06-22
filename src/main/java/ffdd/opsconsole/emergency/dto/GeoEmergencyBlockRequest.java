package ffdd.opsconsole.emergency.dto;

import java.util.List;

public record GeoEmergencyBlockRequest(List<String> countries, String reason, String operator) {
}
