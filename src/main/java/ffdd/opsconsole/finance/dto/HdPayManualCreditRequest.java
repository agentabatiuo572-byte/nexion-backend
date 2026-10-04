package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record HdPayManualCreditRequest(
        Long expectedVersion,
        Long providerVersion,
        BigDecimal receivedVnd,
        String paymentReference,
        OffsetDateTime receivedAt,
        String evidenceRef,
        String reason,
        String operator) {
}
