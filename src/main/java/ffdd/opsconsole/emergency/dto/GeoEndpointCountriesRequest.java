package ffdd.opsconsole.emergency.dto;

import java.util.List;

public record GeoEndpointCountriesRequest(
        List<String> countries,
        String mode,
        String reason,
        String operator,
        String expectedMode,
        List<String> expectedCountries) {
    public GeoEndpointCountriesRequest(List<String> countries, String mode, String reason, String operator) {
        this(countries, mode, reason, operator, null, null);
    }

    public GeoEndpointCountriesRequest(List<String> countries, String reason, String operator) {
        this(countries, "explicit", reason, operator, null, null);
    }
}
