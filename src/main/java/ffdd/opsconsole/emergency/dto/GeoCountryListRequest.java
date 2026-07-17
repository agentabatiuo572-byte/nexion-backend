package ffdd.opsconsole.emergency.dto;

import java.util.List;

/** Replaces one J2 global country list atomically. */
public record GeoCountryListRequest(
        String status,
        List<String> countries,
        List<String> expectedCountries,
        String triggerBasis,
        String reason,
        String operator) {
}
