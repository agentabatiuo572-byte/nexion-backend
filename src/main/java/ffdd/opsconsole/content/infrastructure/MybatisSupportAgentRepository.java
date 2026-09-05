package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.domain.SupportAgentProfileRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView;
import ffdd.opsconsole.content.domain.SupportTicketAssigneeCandidateView;
import ffdd.opsconsole.content.mapper.SupportAgentMapper;
import ffdd.opsconsole.content.mapper.SupportAgentMapper.SupportAgentProfileRow;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisSupportAgentRepository implements SupportAgentRepository {
    private final SupportAgentMapper mapper;
    private final SchemaInitializationGate schemaInitializationGate = new SchemaInitializationGate();

    @Override
    public void ensureSchema() {
        schemaInitializationGate.runOnce(() -> {
            mapper.createProfileTable();
            if (mapper.countSeatTypeColumn() == 0) {
                mapper.addSeatTypeColumn();
            }
            if (mapper.countProfileVersionColumn() == 0) {
                mapper.addProfileVersionColumn();
            }
            mapper.backfillSeatType();
            mapper.createAssignmentTable();
            if (mapper.countAssignmentTypeColumn() > 0) {
                mapper.dropAssignmentTypeColumn();
            }
            mapper.deactivateDuplicateActiveAssignments();
            if (mapper.countActiveUserColumn() == 0) {
                mapper.addActiveUserColumn();
            }
            if (mapper.countActiveUserUniqueIndex() == 0) {
                mapper.addActiveUserUniqueIndex();
            }
        });
    }

    @Override
    public List<SupportTicketAssigneeCandidateView> listTicketAssigneeCandidates() {
        List<SupportTicketAssigneeCandidateView> candidates = mapper.listTicketAssigneeCandidates();
        return candidates == null ? List.of() : List.copyOf(candidates);
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
            String seatType,
            String position,
            List<String> serviceTypes,
            List<String> tags,
            int maxConcurrent,
            LocalDateTime now) {
        mapper.ensureDefaultProfile(adminId, seatType, position, join(serviceTypes), join(tags), maxConcurrent, now);
    }

    @Override
    public void updateProfile(
            Long adminId,
            String seatType,
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
                seatType,
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
    public boolean updateProfileCas(
            Long adminId, String seatType, String position, List<String> serviceTypes, List<String> tags,
            int maxConcurrent, boolean enabled, boolean transferable, boolean busy,
            long expectedVersion, LocalDateTime now) {
        return mapper.updateProfileCas(
                adminId, seatType, position, join(serviceTypes), join(tags), maxConcurrent,
                enabled ? 1 : 0, transferable ? 1 : 0, busy ? 1 : 0, expectedVersion, now) == 1;
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
    public List<Long> findExistingUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return mapper.listActiveUserIds(userIds);
    }

    @Override
    public List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds) {
        return mapper.listActiveAssignments(agentAdminIds == null ? List.of() : agentAdminIds);
    }

    @Override
    public SupportAgentAssignmentView upsertAssignment(
            Long agentAdminId,
            Long userId,
            String operator,
            String reason,
            LocalDateTime now) {
        mapper.deactivateActiveAssignmentsForUser(userId, operator, reason, now);
        mapper.insertAssignment(agentAdminId, userId, operator, reason, now);
        return mapper.findActiveAssignment(agentAdminId, userId);
    }

    @Override
    public List<SupportAgentAssignmentView> upsertAssignments(
            Long agentAdminId,
            List<Long> userIds,
            String operator,
            String reason,
            LocalDateTime now) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        mapper.deactivateActiveAssignmentsForUsers(userIds, operator, reason, now);
        mapper.insertAssignments(agentAdminId, userIds, operator, reason, now);
        var rowsByUserId = mapper.listActiveAssignmentsForUsers(agentAdminId, userIds).stream()
                .collect(Collectors.toMap(
                        SupportAgentAssignmentView::userId,
                        row -> row,
                        (left, right) -> left));
        return userIds.stream()
                .map(userId -> Optional.ofNullable(rowsByUserId.get(userId))
                        .orElseThrow(() -> new IllegalStateException("SUPPORT_ADVISOR_BATCH_WRITE_INCOMPLETE")))
                .toList();
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

    @Override
    public Optional<DedicatedAdvisorBindingView> findActiveDedicatedAdvisor(Long userId) {
        return Optional.ofNullable(mapper.findActiveDedicatedAdvisor(userId));
    }

    private SupportAgentProfileRecord toProfileRecord(SupportAgentProfileRow row) {
        return new SupportAgentProfileRecord(
                row.adminId(),
                row.seatType(),
                row.position(),
                split(row.serviceTypes()),
                split(row.tags()),
                row.maxConcurrent(),
                row.enabled() != null && row.enabled() == 1,
                row.transferable() != null && row.transferable() == 1,
                row.busy() != null && row.busy() == 1,
                row.version() == null ? 1L : row.version(),
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

    private static final class SchemaInitializationGate {
        private final AtomicBoolean ready = new AtomicBoolean();

        private synchronized void runOnce(Runnable initialization) {
            if (ready.get()) {
                return;
            }
            initialization.run();
            ready.set(true);
        }
    }
}
