package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportAgentRepository {
    void ensureSchema();

    List<SupportAgentProfileRecord> listProfiles(List<Long> adminIds);

    Optional<SupportAgentProfileRecord> findProfile(Long adminId);

    void ensureDefaultProfile(
            Long adminId,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            LocalDateTime now);

    void updateProfile(
            Long adminId,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            boolean enabled,
            boolean transferable,
            boolean busy,
            LocalDateTime now);

    long countActiveAssignments(Long agentAdminId);

    List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds);

    SupportAgentAssignmentView upsertAssignment(
            Long agentAdminId,
            Long userId,
            String assignmentType,
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
