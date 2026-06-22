package ffdd.opsconsole.device.dto;

public record DeviceReviewUpsertRequest(
        String skuId,
        String author,
        Integer rating,
        String content,
        String dateText,
        String status,
        String reason,
        String operator) {
}
