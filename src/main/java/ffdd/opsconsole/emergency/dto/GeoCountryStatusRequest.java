package ffdd.opsconsole.emergency.dto;

public record GeoCountryStatusRequest(String status, String triggerBasis, String reason, String operator, String expectedStatus) {
    public GeoCountryStatusRequest(String status, String triggerBasis, String reason, String operator) {
        this(status, triggerBasis, reason, operator, null);
    }

    public GeoCountryStatusRequest(String status, String reason, String operator) {
        this(status, null, reason, operator, null);
    }
}
