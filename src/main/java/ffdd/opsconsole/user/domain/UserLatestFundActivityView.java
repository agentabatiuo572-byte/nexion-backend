package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Latest source record, not a claim that the payment has settled. */
public record UserLatestFundActivityView(
        String sourceType,
        Long sourceId,
        String bizNo,
        LocalDateTime createdAt,
        BigDecimal amount,
        String currency,
        String status,
        BigDecimal paymentAmount,
        String paymentCurrency) {
}
