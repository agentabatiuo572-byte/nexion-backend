package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportAgentRepository {
    void ensureSchema();

    /** Pure SELECT projection for M2. Must not initialize schemas or materialize account/profile rows. */
    List<SupportTicketAssigneeCandidateView> listTicketAssigneeCandidates();

    List<SupportAgentProfileRecord> listProfiles(List<Long> adminIds);

    Optional<SupportAgentProfileRecord> findProfile(Long adminId);

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

    boolean userExists(Long userId);

    List<Long> findExistingUserIds(List<Long> userIds);

    List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds);

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
