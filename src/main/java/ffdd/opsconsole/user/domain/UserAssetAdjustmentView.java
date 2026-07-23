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
        BigDecimal amountUsd,
        String amountLabel,
        String reasonCode,
        String reason,
        String evidenceRef,
        String idempotencyKey,
        String reversalOf,
        String reversedBy,
        String maker,
        String checker,
        String status,
        String statusLabel,
        String statusTone,
        boolean credit,
        boolean escalated,
        Long ledgerId,
        BigDecimal balanceAfter,
        String sink,
        String reviewReason,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
