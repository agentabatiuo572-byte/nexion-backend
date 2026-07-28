package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/**
 * A server-authoritative business fact that may trigger one Nova cadence channel.
 *
 * <p>The fact comes from the durable A4 outbox. A missing userId is only valid for
 * explicitly broadcast adapters such as the market channel.
 */
public record NovaBusinessEventFact(
        String sourceEventId,
        String eventName,
        Long userId,
        String phase,
        Integer accountAgeMonths,
        String cohort,
        LocalDateTime occurredAt) {
}
