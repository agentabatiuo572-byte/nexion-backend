package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TopupAdmissionReceipt(
        String admissionEventId,
        String requestHash,
        String orderNo,
        Long userId,
        String provider,
        BigDecimal amountUsdt,
        BigDecimal feeAmountUsdt,
        BigDecimal feeRatePct,
        String threeDsStatus,
        String cardBin,
        String clientIp,
        String deviceFingerprint,
        String decision,
        String reason,
        LocalDateTime expiresAt,
        String settlementEventId,
        String failureEventId) {
}
