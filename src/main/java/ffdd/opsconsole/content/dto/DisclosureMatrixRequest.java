package ffdd.opsconsole.content.dto;

import java.util.List;

public record DisclosureMatrixRequest(
        String jurisdictionCode,
        String jurisdictionName,
        List<String> countryCodes,
        String version,
        String status,
        String expectedVersion,
        String expectedStatus,
        List<String> expectedCountryCodes,
        String operator,
        String reason) {

    public DisclosureMatrixRequest(String jurisdictionCode, String jurisdictionName,
                                   List<String> countryCodes, String version, String status,
                                   String operator, String reason) {
        this(jurisdictionCode, jurisdictionName, countryCodes, version, status,
                null, null, List.of(), operator, reason);
    }

    public DisclosureMatrixRequest(String jurisdictionCode, String jurisdictionName, String version,
                                   String status, String operator, String reason) {
        this(jurisdictionCode, jurisdictionName, List.of(), version, status,
                null, null, List.of(), operator, reason);
    }
}
