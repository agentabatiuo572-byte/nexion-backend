package ffdd.opsconsole.device.dto;

public record E3ConfigUpdateRequest(
        String key,
        String value,
        String reason,
        String operator) {
}
