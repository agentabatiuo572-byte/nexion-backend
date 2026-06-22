package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DepositFlowView(
        Long id,
        Long userId,
        String depositNo,
        String channel,
        String asset,
        BigDecimal amount,
        BigDecimal providerReceived,
        String proof,
        String status,
        String statusLabel,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt,
        LocalDateTime creditedAt) {
}
