package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record NovaSocialEventView(
        Long id,
        String eventType,
        String sourceEventId,
        String actorDisplay,
        String cityDisplay,
        String amountDisplay,
        String sourceNote,
        String sourceSystem,
        String sourceTable,
        String status,
        LocalDateTime occurredAt,
        LocalDateTime expiresAt,
        LocalDateTime verifiedAt,
        LocalDateTime lastDispatchedAt,
        Long dispatchCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
