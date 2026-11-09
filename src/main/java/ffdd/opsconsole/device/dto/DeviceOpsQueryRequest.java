package ffdd.opsconsole.device.dto;

public record DeviceOpsQueryRequest(
        String keyword, String simState, String source, String productCode, Long pageNum, Long pageSize) {
}
