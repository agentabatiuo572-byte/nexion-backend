package ffdd.opsconsole.device.dto;

public record DeviceSkuStatusRequest(
        String status,
        String reason,
        String operator) {
}
