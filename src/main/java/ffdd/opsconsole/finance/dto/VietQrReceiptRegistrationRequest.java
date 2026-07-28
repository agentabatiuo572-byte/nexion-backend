package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record VietQrReceiptRegistrationRequest(
        Long bankAccountId,
        String paymentReference,
        String memoCode,
        BigDecimal receivedVnd,
        OffsetDateTime receivedAt,
        String evidenceRef,
        String reason,
        String operator) {
}
