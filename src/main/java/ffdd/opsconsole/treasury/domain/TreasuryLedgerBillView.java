package ffdd.opsconsole.treasury.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TreasuryLedgerBillView(
        Long id,
        Long userId,
        String userNo,
        String nickname,
        String bizNo,
        String bizType,
        String asset,
        String direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
