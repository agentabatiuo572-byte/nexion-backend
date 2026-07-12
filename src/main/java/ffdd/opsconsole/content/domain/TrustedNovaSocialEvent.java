package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Internal DTO populated only by whitelisted, parameterized business-table collectors. */
public record TrustedNovaSocialEvent(
        String eventType,
        String sourceEventId,
        String sourceSystem,
        String sourceTable,
        String actorName,
        String city,
        BigDecimal amount,
        String amountUnit,
        String sourceNote,
        LocalDateTime occurredAt) {
}
