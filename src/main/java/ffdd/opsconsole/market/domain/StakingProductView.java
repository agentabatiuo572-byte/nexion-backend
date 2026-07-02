package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;

public record StakingProductView(
        Long id,
        String productCode,
        String productName,
        String asset,
        Integer termDays,
        BigDecimal apyBps,
        BigDecimal earlyPenaltyBps,
        BigDecimal minAmount,
        BigDecimal rewardMultiplier,
        Integer ticketPerOrder,
        String presetAmounts,
        Integer sortOrder,
        String status,
        BigDecimal lockedUsd,
        Long positionCount) {
}
