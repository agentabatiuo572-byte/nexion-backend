package ffdd.opsconsole.device.dto;

public record DeviceTradeinActionRequest(Long deviceId, String reason, String operator) {
}
