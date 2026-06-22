package ffdd.opsconsole.device.dto;

public record DeviceTaskQueryRequest(
        String status,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
