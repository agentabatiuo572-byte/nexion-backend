package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceOrderView(
        String orderNo,
        String userNo,
        String skuId,
        String skuName,
        BigDecimal amount,
        String state,
        String dcLocation,
        String ageText,
        LocalDateTime orderedAt,
        LocalDateTime updatedAt) {
}
