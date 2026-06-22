package ffdd.opsconsole.device.dto;

public record DeviceReviewQueryRequest(
        String skuId,
        String status,
        Integer rating,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
