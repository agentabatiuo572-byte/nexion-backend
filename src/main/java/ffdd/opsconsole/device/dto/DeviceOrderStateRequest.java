package ffdd.opsconsole.device.dto;

public record DeviceOrderStateRequest(
        String state,
        String reason,
        String operator) {
}
