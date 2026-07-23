package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TopupProviderStatementRequest(
        String ingestionEventId,
        String statementNo,
        String provider,
        String channelCode,
        String providerReference,
        Long userId,
        BigDecimal amountUsdt,
        String statementStatus,
        String evidenceRef,
        LocalDateTime observedAt) {
}
