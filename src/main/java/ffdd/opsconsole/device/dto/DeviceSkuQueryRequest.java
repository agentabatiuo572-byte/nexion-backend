package ffdd.opsconsole.device.dto;

public record DeviceSkuQueryRequest(
        String status,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
