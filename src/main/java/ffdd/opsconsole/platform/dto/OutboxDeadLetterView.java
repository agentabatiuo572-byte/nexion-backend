package ffdd.opsconsole.platform.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Five fixed weekly H3 dead-letter counts. {@code bothLayersDeadCount} only means the outbox event and its
 * {@code h3-quest-completion} delivery are both currently DEAD; it is not a complete redrive-eligibility decision.
 *
 * <p>Times are deliberately layer-specific: {@code oldestOutboxLastStateAt} is the outbox row's last state write,
 * while {@code oldestBothLayersDeadObservedAt} is the earliest observable time at which both current DEAD snapshots
 * existed. Neither is the original first-death timestamp.</p>
 */
public record OutboxDeadLetterView(List<Type> types) {
    public record Type(
            String questCode,
            String eventType,
            long outboxDeadCount,
            long bothLayersDeadCount,
            LocalDateTime oldestOutboxLastStateAt,
            LocalDateTime oldestBothLayersDeadObservedAt,
            boolean bothLayersDeadTimeComplete) {
    }
}
