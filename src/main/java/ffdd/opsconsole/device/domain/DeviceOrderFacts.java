package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceOrderFacts(
        String orderNo,
        Long userId,
        Integer quantity,
        String orderType,
        BigDecimal subtotalUsdt,
        BigDecimal discountUsdt,
        BigDecimal amountUsdt,
        String paymentNo,
        String paymentMethod,
        String paymentStatus,
        String orderStatus,
        String activationStatus,
        Long productId,
        String productNo,
        String productName,
        Long deviceId,
        String deviceInstanceNo,
        String dcLocation,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        LocalDateTime activatedAt,
        LocalDateTime updatedAt) {
}
