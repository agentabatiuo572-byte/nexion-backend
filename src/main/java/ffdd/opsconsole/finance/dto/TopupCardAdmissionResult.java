package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TopupCardAdmissionResult(
        String admissionEventId,
        String decision,
        String reason,
        BigDecimal authorizedAmountUsdt,
        BigDecimal feeAmountUsdt,
        BigDecimal feeRatePct,
        LocalDateTime expiresAt,
        boolean replay) {
}
