package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StakingPositionView(
        Long id,
        Long userId,
        String userNo,
        String nickname,
        String positionNo,
        String productCode,
        String productName,
        BigDecimal amountUsdt,
        BigDecimal apyBps,
        BigDecimal earlyPenaltyBps,
        Integer termDays,
        LocalDateTime lockedAt,
        LocalDateTime unlockAt,
        BigDecimal estimatedInterestUsdt,
        String status,
        String statusLabel,
        String statusTone,
        LocalDateTime claimedAt,
        LocalDateTime earlyWithdrawnAt) {
}
