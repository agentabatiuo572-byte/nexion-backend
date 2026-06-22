package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserAssetAdjustmentView(
        String adjustmentNo,
        Long userId,
        String userNo,
        String nickname,
        String asset,
        String direction,
        BigDecimal amount,
        String amountLabel,
        String reasonCode,
        String reason,
        String maker,
        String checker,
        String status,
        String statusLabel,
        String statusTone,
        boolean credit,
        boolean escalated,
        Long ledgerId,
        String sink,
        String reviewReason,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
