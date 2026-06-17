package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WithdrawalOrderView(
        Long id,
        Long userId,
        String withdrawalNo,
        String asset,
        String chain,
        BigDecimal amount,
        BigDecimal fee,
        String targetAddress,
        Long riskDecisionId,
        String chainTxHash,
        String status,
        LocalDateTime chainSubmittedAt,
        LocalDateTime completedAt,
        LocalDateTime failedAt,
        String failureReason,
        Integer chainBroadcastAttempts,
        LocalDateTime nextBroadcastAt,
        String lastBroadcastError,
        LocalDateTime broadcastDeadAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
