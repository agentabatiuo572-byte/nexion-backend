package ffdd.opsconsole.device.dto;

public record DeviceTaskQueryRequest(
        String status,
        String keyword,
        String taskClass,
        Long pageNum,
        Long pageSize) {}
