package ffdd.opsconsole.emergency.dto;

public record EmergencyConfigUpdateRequest(
        String value,
        String reason,
        String operator) {
}
