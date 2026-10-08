package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.Set;

/** Current projections retain stable IDs; a missing historical record is not an ungrouped fact. */
public final class SupportGroupFacts {
    private SupportGroupFacts() {}
    public enum ReadMode { PERSONAL, MANAGED, ALL }
    /** Invalidation notice only; consumers must still authorize against current database facts. */
    public record ScopeChanged(Set<Long> affectedAdminIds, String reason) {
        public ScopeChanged { affectedAdminIds=Set.copyOf(affectedAdminIds); }
    }
    /** Identity and narrowing filters only; every query must recheck current authorization. */
    public record ReadScope(Long actorId, ReadMode mode, Long requestedGroupId, Long requestedAgentId) {
        public ReadScope {
            if (!validId(actorId) || mode == null
                    || (requestedGroupId != null && !validId(requestedGroupId))
                    || (requestedAgentId != null && !validId(requestedAgentId)))
                throw new IllegalArgumentException("SUPPORT_READ_SCOPE_INVALID");
            if (mode == ReadMode.PERSONAL && (requestedGroupId != null
                    || (requestedAgentId != null && !actorId.equals(requestedAgentId))))
                throw new IllegalArgumentException("SUPPORT_PERSONAL_SCOPE_INVALID");
        }
        private static boolean validId(Long id) { return id != null && id > 0 && id <= 9007199254740991L; }
    }
    public record Group(Long id, String name, Long supervisorAdminId, String status, Long version) {}
    public record Account(Long id, Integer status, Long version) {}
    public record Qualification(Long id, Long adminId, String qualificationKind, String state,
                                Long version, LocalDateTime startsAt) {}
    public record Member(Long id, Long agentAdminId, Long groupId, Long version, LocalDateTime startsAt) {}
    public record Route(Long id,Long customerId,Long groupId,Long version,LocalDateTime startsAt) {}
    public record Owner(Long id,Long groupId,Long supervisorAdminId,Long version,LocalDateTime startsAt) {}
    public record Candidate(Long agentId,Long accountVersion,Long profileVersion,Long memberId,Long memberVersion,
                            Long qualificationId,Long qualificationVersion) {}
}
