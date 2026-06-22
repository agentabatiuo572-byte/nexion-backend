package ffdd.opsconsole.device.domain;

import java.time.LocalDateTime;

public record DeviceReviewView(
        String reviewId,
        String skuId,
        String skuName,
        String author,
        Integer rating,
        String content,
        String dateText,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
