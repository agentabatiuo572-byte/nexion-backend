package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/** Current projections retain stable IDs; a missing historical record is not an ungrouped fact. */
public final class SupportGroupFacts {
    private SupportGroupFacts() {}
    public record Group(Long id, String name, Long supervisorAdminId, String status, Long version) {}
    public record Account(Long id, Integer status, Long version) {}
    public record Qualification(Long id, Long adminId, String qualificationKind, String state,
                                Long version, LocalDateTime startsAt) {}
    public record Member(Long id, Long agentAdminId, Long groupId, Long version, LocalDateTime startsAt) {}
}
