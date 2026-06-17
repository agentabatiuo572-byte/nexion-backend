package ffdd.opsconsole.device.dto;

public record DeviceOpsQueryRequest(
        String status,
        String dcLocation,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
