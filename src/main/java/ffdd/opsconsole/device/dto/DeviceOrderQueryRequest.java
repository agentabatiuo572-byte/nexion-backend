package ffdd.opsconsole.device.dto;

public record DeviceOrderQueryRequest(
        String state,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
