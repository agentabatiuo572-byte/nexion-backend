package ffdd.opsconsole.device.dto;

public record DeviceReviewStatusRequest(
        String status,
        String reason,
        String operator) {
}
