package ffdd.opsconsole.emergency.dto;

public record EmergencyConfigUpdateRequest(
        String value,
        String expectedValue,
        String reason,
        String operator) {
}
