package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExchangeOrderView(
        Long id,
        Long userId,
        String userNo,
        String nickname,
        String countryCode,
        String exchangeNo,
        String fromAsset,
        String toAsset,
        BigDecimal fromAmount,
        BigDecimal toAmount,
        BigDecimal rate,
        String status,
        String statusLabel,
        String statusTone,
        String directionLabel,
        BigDecimal amountUsdt,
        String gateType,
        String gateReason,
        String etaLabel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public ExchangeOrderView withQueueEstimate(String copy) {
        if (!"QUEUED".equalsIgnoreCase(status)) return this;
        return new ExchangeOrderView(id, userId, userNo, nickname, countryCode, exchangeNo, fromAsset,
                toAsset, fromAmount, toAmount, rate, status, statusLabel, statusTone, directionLabel,
                amountUsdt, gateType, gateReason, copy, createdAt, updatedAt);
    }
}
