package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public final class SupportMaintenance {
    private SupportMaintenance() {}
    public record Preference(Long customerId, boolean enabled, Long version) {}
    public record ActivityState(Long customerId, long activitySeq, LocalDateTime lastEffectiveAt) {}
    public record ActivityEvent(Long id, Long customerId, long seq, String sourceRef, LocalDateTime occurredAt) {}
    public record Cycle(Long id, Long customerId, Long assignmentId, Long agentAdminId, String status,
                        long baselineActivitySeq, LocalDateTime openedAt, LocalDateTime lastExecutionAt,
                        LocalDateTime closedAt, Long successEventId) {}
}
