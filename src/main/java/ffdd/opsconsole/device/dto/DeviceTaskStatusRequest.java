package ffdd.opsconsole.device.dto;

public record DeviceTaskStatusRequest(
        String status,
        String reason,
        String operator) {
}
