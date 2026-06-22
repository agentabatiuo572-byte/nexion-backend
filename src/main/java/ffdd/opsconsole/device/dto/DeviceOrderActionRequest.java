package ffdd.opsconsole.device.dto;

public record DeviceOrderActionRequest(
        String terminalState,
        String reason,
        String operator) {
}
