package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportAgentRepository {
    void ensureSchema();

    /** Pure SELECT projection for M2. Must not initialize schemas or materialize account/profile rows. */
    List<SupportTicketAssigneeCandidateView> listTicketAssigneeCandidates();

    SupportOperatorScope supportOperatorScope(Long visibleAdminId);

    default SupportOperatorScope scopedSupportOperators(ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        java.util.Objects.requireNonNull(scope, "SUPPORT_READ_SCOPE_REQUIRED");
        SupportOperatorScope roles = supportOperatorScope(scope.mode() == SupportGroupFacts.ReadMode.PERSONAL ? scope.actorId() : null);
        return new SupportOperatorScope(roles.visibleAdminId(), roles.supportRoleIds(), roles.unusablePrimaryRoleIds(), roles.superFallbackToSupport(), scope);
    }

    long countSupportOperators(SupportOperatorScope scope);

    List<SupportOperatorRecord> pageSupportOperators(SupportOperatorScope scope, long limit, long offset);

    record SupportOperatorRecord(Long adminId, String name, String email, String avatarAssetId, Long avatarVersion, String status) {
        public SupportOperatorRecord(Long adminId, String name, String email, String avatarAssetId, Long avatarVersion) {
            this(adminId, name, email, avatarAssetId, avatarVersion, "enabled");
        }
    }

    record SupportOperatorScope(Long visibleAdminId, List<Long> supportRoleIds,
                                List<Long> unusablePrimaryRoleIds, boolean superFallbackToSupport, SupportGroupFacts.ReadScope readScope) {
        public SupportOperatorScope(Long visibleAdminId, List<Long> supportRoleIds, List<Long> unusablePrimaryRoleIds, boolean superFallbackToSupport) {
            this(visibleAdminId, supportRoleIds, unusablePrimaryRoleIds, superFallbackToSupport, null);
        }
        public Long actorId() { return readScope == null ? null : readScope.actorId(); }
        public SupportGroupFacts.ReadMode mode() { return readScope == null ? null : readScope.mode(); }
        public Long requestedGroupId() { return readScope == null ? null : readScope.requestedGroupId(); }
        public Long requestedAgentId() { return readScope == null ? null : readScope.requestedAgentId(); }
    }

    List<SupportAgentProfileRecord> listProfiles(List<Long> adminIds);

    Optional<SupportAgentProfileRecord> findProfile(Long adminId);

    Optional<AppSupportAdvisorView> findAppAdvisor(Long userId);

    default Optional<DedicatedAdvisorBindingView> findActiveDedicatedAdvisor(Long userId) {
        return Optional.empty();
    }

    void ensureDefaultProfile(
            Long adminId,
            String seatType,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            LocalDateTime now);

    void updateProfile(
            Long adminId,
            String seatType,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            boolean enabled,
            boolean transferable,
            boolean busy,
            LocalDateTime now);

    boolean updateProfileCas(
            Long adminId,
            String seatType,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            boolean enabled,
            boolean transferable,
            boolean busy,
            long expectedVersion,
            LocalDateTime now);

    long countActiveAssignments(Long agentAdminId);

    long countActiveAssignments(Long agentAdminId, SupportGroupFacts.ReadScope scope);

    boolean userExists(Long userId);

    List<Long> findExistingUserIds(List<Long> userIds);

    List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds);

    List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds, SupportGroupFacts.ReadScope scope);

    SupportAgentAssignmentView upsertAssignment(
            Long agentAdminId,
            Long userId,
            String operator,
            String reason,
            LocalDateTime now);

    List<SupportAgentAssignmentView> upsertAssignments(
            Long agentAdminId,
            List<Long> userIds,
            String operator,
            String reason,
            LocalDateTime now);

    Optional<SupportAgentAssignmentView> deactivateAssignment(
            Long agentAdminId,
            Long assignmentId,
            String operator,
            String reason,
            LocalDateTime now);
}
