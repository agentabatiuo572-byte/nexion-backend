package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DepositChargebackView(
        String caseNo,
        Long userId,
        String userCode,
        BigDecimal amount,
        String reasonCode,
        String enteredStatus,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
