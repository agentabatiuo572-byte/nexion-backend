package ffdd.opsconsole.emergency.dto;

import java.util.List;

public record GeoEndpointCountriesRequest(List<String> countries, String reason, String operator) {
}
