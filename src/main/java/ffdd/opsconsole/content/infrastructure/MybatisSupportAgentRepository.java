package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.domain.SupportAgentProfileRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.mapper.SupportAgentMapper;
import ffdd.opsconsole.content.mapper.SupportAgentMapper.SupportAgentProfileRow;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisSupportAgentRepository implements SupportAgentRepository {
    private final SupportAgentMapper mapper;

    @Override
    public void ensureSchema() {
        mapper.createProfileTable();
        mapper.createAssignmentTable();
    }

    @Override
    public List<SupportAgentProfileRecord> listProfiles(List<Long> adminIds) {
        return mapper.listProfiles(adminIds == null ? List.of() : adminIds).stream()
                .map(this::toProfileRecord)
                .toList();
    }

    @Override
    public Optional<SupportAgentProfileRecord> findProfile(Long adminId) {
        return Optional.ofNullable(mapper.findProfile(adminId)).map(this::toProfileRecord);
    }

    @Override
    public void ensureDefaultProfile(
            Long adminId,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            LocalDateTime now) {
        mapper.ensureDefaultProfile(adminId, position, join(serviceTypes), join(tags), maxConcurrent, now);
    }

    @Override
    public void updateProfile(
            Long adminId,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            boolean enabled,
            boolean transferable,
            boolean busy,
            LocalDateTime now) {
        mapper.updateProfile(
                adminId,
                position,
                join(serviceTypes),
                join(tags),
                maxConcurrent,
                enabled ? 1 : 0,
                transferable ? 1 : 0,
                busy ? 1 : 0,
                now);
    }

    @Override
    public long countActiveAssignments(Long agentAdminId) {
        return mapper.countActiveAssignments(agentAdminId);
    }

    @Override
    public boolean userExists(Long userId) {
        return userId != null && mapper.countActiveUser(userId) > 0;
    }

    @Override
    public List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds) {
        return mapper.listActiveAssignments(agentAdminIds == null ? List.of() : agentAdminIds);
    }

    @Override
    public SupportAgentAssignmentView upsertAssignment(
            Long agentAdminId,
            Long userId,
            String assignmentType,
            String operator,
            String reason,
            LocalDateTime now) {
        mapper.deactivateSameAssignment(agentAdminId, userId, assignmentType, operator, reason, now);
        mapper.insertAssignment(agentAdminId, userId, assignmentType, operator, reason, now);
        return mapper.findActiveAssignment(agentAdminId, userId, assignmentType);
    }

    @Override
    public Optional<SupportAgentAssignmentView> deactivateAssignment(
            Long agentAdminId,
            Long assignmentId,
            String operator,
            String reason,
            LocalDateTime now) {
        SupportAgentAssignmentView before = mapper.findAssignmentById(agentAdminId, assignmentId);
        if (before == null || !"ACTIVE".equals(before.status())) {
            return Optional.empty();
        }
        mapper.deactivateAssignment(agentAdminId, assignmentId, operator, reason, now);
        SupportAgentAssignmentView after = mapper.findAssignmentById(agentAdminId, assignmentId);
        return Optional.ofNullable(after == null ? before : after);
    }

    private SupportAgentProfileRecord toProfileRecord(SupportAgentProfileRow row) {
        return new SupportAgentProfileRecord(
                row.adminId(),
                row.position(),
                split(row.serviceTypes()),
                split(row.tags()),
                row.maxConcurrent(),
                row.enabled() != null && row.enabled() == 1,
                row.transferable() != null && row.transferable() == 1,
                row.busy() != null && row.busy() == 1,
                row.updatedAt());
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private List<String> split(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }
}
