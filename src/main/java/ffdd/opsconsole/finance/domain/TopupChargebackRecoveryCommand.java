package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record TopupChargebackRecoveryCommand(
        String caseNo,
        Long userId,
        BigDecimal amount,
        BigDecimal feeBufferRequired,
        String evidenceRef,
        String reason,
        String operator,
        String idempotencyKey) {
}
