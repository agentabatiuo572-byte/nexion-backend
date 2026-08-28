package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AppComputeReceiptSummaryView(
        String receiptNo,
        String taskNo,
        String taskClass,
        String model,
        String client,
        BigDecimal rewardUsdt,
        BigDecimal rewardNex,
        String earningStatus,
        LocalDateTime completedAt) {
}
