package ffdd.opsconsole.device.dto;

public record DeviceEarlyAccessUpdateRequest(
        Boolean enabled,
        Integer leadDays,
        String reason,
        String operator) {
}
