package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceOrderView(
        String orderNo,
        String userNo,
        String skuId,
        String skuName,
        String skuSource,
        BigDecimal amount,
        String state,
        String dcLocation,
        String ageText,
        LocalDateTime orderedAt,
        LocalDateTime updatedAt,
        BigDecimal refundAmountUsdt) {

    public DeviceOrderView(String orderNo, String userNo, String skuId, String skuName, String skuSource,
                           BigDecimal amount, String state, String dcLocation, String ageText,
                           LocalDateTime orderedAt, LocalDateTime updatedAt) {
        this(orderNo, userNo, skuId, skuName, skuSource, amount, state, dcLocation, ageText,
                orderedAt, updatedAt, null);
    }
}
