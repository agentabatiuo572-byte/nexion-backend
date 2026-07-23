package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.domain.SupportAgentOverview;
import ffdd.opsconsole.content.domain.SupportAgentPageView;
import ffdd.opsconsole.content.domain.SupportAgentProfileRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.dto.SupportAgentAssignmentRequest;
import ffdd.opsconsole.content.dto.SupportAgentQueryRequest;
import ffdd.opsconsole.content.dto.SupportAgentProfileUpdateRequest;
import ffdd.opsconsole.content.dto.SupportAgentSeatAssignmentRequest;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsSupportAgentServiceTest {
    private final SupportAgentRepository repository = new FakeSupportAgentRepository();
    private final OpsAdminAccountService accountService = mock(OpsAdminAccountService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsSupportAgentService service = new OpsSupportAgentService(
            repository,
            accountService,
            auditLogService,
            idempotencyService,
            OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            clock);

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .execute(anyString(), anyString(), anyString(), any(), any());
        when(accountService.overview()).thenReturn(ApiResult.ok(adminOverview(List.of(
                operator("1", "Root Admin", "super", "enabled"),
                operator("2", "Support Agent", "support", "enabled"),
                operator("3", "Disabled Support", "support", "disabled"),
                operator("4", "Finance Agent", "finance", "enabled")))));
        when(accountService.currentOperator()).thenReturn(Optional.of(operator("1", "Root Admin", "super", "enabled")));
    }

    @Test
    void overviewReadsExistingSupportProfilesOnly() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());

        ApiResult<SupportAgentOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().agents()).extracting("adminId").containsExactly(2L);
        assertThat(result.getData().agents().get(0).seatType()).isEqualTo("GENERAL");
        assertThat(result.getData().agents().get(0).position()).isEqualTo("通用客服");
        assertThat(result.getData().transferTargets())
                .anySatisfy(target -> assertThat(target).containsEntry("targetType", "agent").containsEntry("targetId", "2"));
        assertThat(result.getData().sources()).contains("nx_admin", "nx_support_agent_profile", "nx_support_agent_user_assignment");
        assertThat(fake.seededAdminIds).isEmpty();
    }

    @Test
    void agentsReturnPagedBackendRowsAndCurrentPageAssignments() {
        when(accountService.overview()).thenReturn(ApiResult.ok(adminOverview(List.of(
                operator("2", "Support Agent A", "support", "enabled"),
                operator("5", "Support Agent B", "support", "enabled"),
                operator("6", "Support Agent C", "support", "enabled"),
                operator("7", "Support Agent D", "support", "enabled"),
                operator("8", "Disabled Support", "support", "disabled")))));
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.updateProfile(5L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.updateProfile(6L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.updateProfile(7L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.assignments.add(new SupportAgentAssignmentView(10L, 2L, 1001L, "U00001001", "用户1001", "ACTIVE", now().toString(), null, "system", "seed", now().toString()));
        fake.assignments.add(new SupportAgentAssignmentView(11L, 6L, 1006L, "U00001006", "用户1006", "ACTIVE", now().toString(), null, "system", "seed", now().toString()));

        ApiResult<SupportAgentPageView> result = service.agents(new SupportAgentQueryRequest(2L, 2L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().total()).isEqualTo(4);
        assertThat(result.getData().pageNum()).isEqualTo(2);
        assertThat(result.getData().pageSize()).isEqualTo(2);
        assertThat(result.getData().records()).extracting("adminId").containsExactly(6L, 7L);
        assertThat(result.getData().advisorAssignments()).extracting(SupportAgentAssignmentView::agentAdminId).containsExactly(6L);
    }

    @Test
    void updateProfileRequiresStructuredFieldsAndAudits() {
        SupportAgentProfileUpdateRequest request = new SupportAgentProfileUpdateRequest(
                "通用客服",
                List.of("support"),
                List.of("高价值用户", "KYC"),
                16,
                true,
                true,
                false,
                "superadmin",
                "调整通用客服岗位");

        var result = service.updateProfile(2L, "idem-profile", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().adminId()).isEqualTo(2L);
        assertThat(result.getData().seatType()).isEqualTo("GENERAL");
        assertThat(result.getData().position()).isEqualTo("通用客服");
        assertThat(result.getData().serviceTypes()).containsExactly("support");
        assertThat(result.getData().maxConcurrent()).isEqualTo(16);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("M5_SUPPORT_AGENT_PROFILE_CHANGED");
        assertThat(captor.getValue().getResourceId()).isEqualTo("2");
    }

    @Test
    void updateProfileDoesNotChangeSeatTypeFromProfilePayload() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "DEDICATED", "专属客服", List.of("advisor"), List.of("高价值用户"), 20, true, true, false, now());

        SupportAgentProfileUpdateRequest request = new SupportAgentProfileUpdateRequest(
                "通用客服",
                List.of("support"),
                List.of("夜班"),
                14,
                true,
                true,
                false,
                "superadmin",
                "调整客服接派单配置");

        var result = service.updateProfile(2L, "idem-profile-seat-guard", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().seatType()).isEqualTo("DEDICATED");
        assertThat(result.getData().position()).isEqualTo("专属客服");
        assertThat(result.getData().serviceTypes()).contains("advisor");
        assertThat(fake.findProfile(2L).orElseThrow().seatType()).isEqualTo("DEDICATED");
    }

    @Test
    void supportMutationReasonUsesEightToTwoHundredCharacterBoundary() {
        SupportAgentProfileUpdateRequest tooShortRequest = new SupportAgentProfileUpdateRequest(
                "通用客服",
                List.of("support"),
                List.of("夜班"),
                14,
                true,
                true,
                false,
                "superadmin",
                "1234567");
        SupportAgentProfileUpdateRequest tooLongRequest = new SupportAgentProfileUpdateRequest(
                "通用客服",
                List.of("support"),
                List.of("夜班"),
                14,
                true,
                true,
                false,
                "superadmin",
                "x".repeat(201));

        var tooShort = service.updateProfile(2L, "idem-profile-short", tooShortRequest);
        var tooLong = service.updateProfile(2L, "idem-profile-long", tooLongRequest);

        assertThat(tooShort.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(tooLong.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void updateProfileRejectsNonSupervisorActor() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        when(accountService.currentOperator()).thenReturn(Optional.of(operator("2", "Support Agent", "support", "enabled")));

        SupportAgentProfileUpdateRequest request = new SupportAgentProfileUpdateRequest(
                "通用客服",
                List.of("support"),
                List.of("夜班"),
                14,
                true,
                true,
                false,
                "support.agent",
                "尝试调整客服岗位");

        var result = service.updateProfile(2L, "idem-profile-forbidden", request);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_SEAT_ASSIGNMENT_FORBIDDEN");
    }

    @Test
    void assignAdvisorRequiresAdvisorServiceType() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "DEDICATED", "专属客服", List.of("support"), List.of(), 12, true, true, false, now());

        var result = service.assignAdvisorUser(
                2L,
                "idem-assign",
                new SupportAgentAssignmentRequest(1001L, "superadmin", "绑定专属客服用户"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_AGENT_NOT_ADVISOR");
    }

    @Test
    void assignSeatWritesDedicatedProfileAndUserBinding() {
        var result = service.assignSeat(
                2L,
                "idem-seat",
                new SupportAgentSeatAssignmentRequest(
                        "专属客服",
                        List.of("advisor"),
                        List.of("高价值用户"),
                        30,
                        true,
                        true,
                        false,
                        List.of(1001L),
                        "superadmin",
                        "分配专属客服并绑定用户"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().seatType()).isEqualTo("DEDICATED");
        assertThat(result.getData().position()).isEqualTo("专属客服");
        assertThat(((FakeSupportAgentRepository) repository).assignments)
                .extracting(SupportAgentAssignmentView::userId)
                .contains(1001L);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLogWriteRequest::getAction)
                .contains("M1_SUPPORT_SEAT_ASSIGNED");
    }

    @Test
    void assignSeatRejectsNonSupervisorActor() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        when(accountService.currentOperator()).thenReturn(Optional.of(operator("2", "Support Agent", "support", "enabled")));

        var result = service.assignSeat(
                2L,
                "idem-seat-forbidden",
                new SupportAgentSeatAssignmentRequest(
                        "专属客服",
                        List.of("advisor"),
                        List.of("高价值用户"),
                        16,
                        true,
                        true,
                        false,
                        List.of(1001L),
                        "support.agent",
                        "尝试分配客服坐席"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_SEAT_ASSIGNMENT_FORBIDDEN");
    }

    @Test
    void assignAdvisorReplacesExistingActiveBindingForSameUser() {
        when(accountService.overview()).thenReturn(ApiResult.ok(adminOverview(List.of(
                operator("1", "Root Admin", "super", "enabled"),
                operator("2", "Support Agent A", "support", "enabled"),
                operator("5", "Support Agent B", "support", "enabled")))));
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "DEDICATED", "专属客服", List.of("advisor"), List.of("高价值用户"), 8, true, true, false, now());
        fake.updateProfile(5L, "DEDICATED", "专属客服", List.of("advisor"), List.of("高价值用户"), 8, true, true, false, now());

        var first = service.assignAdvisorUser(
                2L,
                "idem-assign-first",
                new SupportAgentAssignmentRequest(1001L, "superadmin", "首次绑定专属客服用户"));
        var second = service.assignAdvisorUser(
                5L,
                "idem-assign-second",
                new SupportAgentAssignmentRequest(1001L, "superadmin", "改绑专属客服用户"));

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isZero();
        assertThat(fake.assignments)
                .filteredOn(row -> row.userId().equals(1001L) && "ACTIVE".equals(row.status()))
                .extracting(SupportAgentAssignmentView::agentAdminId)
                .containsExactly(5L);
    }

    @Test
    void assignAdvisorRejectsNonSupervisorActor() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "DEDICATED", "专属客服", List.of("advisor"), List.of("高价值用户"), 8, true, true, false, now());
        when(accountService.currentOperator()).thenReturn(Optional.of(operator("2", "Support Agent", "support", "enabled")));

        var result = service.assignAdvisorUser(
                2L,
                "idem-assign-forbidden",
                new SupportAgentAssignmentRequest(1001L, "support.agent", "绑定专属客服用户"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_ADVISOR_ASSIGNMENT_FORBIDDEN");
    }

    @Test
    void assignAdvisorRejectsMissingBusinessUser() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "DEDICATED", "专属客服", List.of("advisor"), List.of("高价值用户"), 8, true, true, false, now());

        var result = service.assignAdvisorUser(
                2L,
                "idem-assign-missing-user",
                new SupportAgentAssignmentRequest(9999L, "superadmin", "绑定专属客服用户"));

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_ADVISOR_USER_NOT_FOUND");
    }

    private static AdminAccountOverview adminOverview(List<AdminAccountOverview.OperatorRecord> operators) {
        return new AdminAccountOverview(
                new AdminAccountOverview.AdminAccountStats(operators.size(), 1, 0, 0, 1, 0),
                List.of(),
                operators,
                List.of(),
                List.of());
    }

    private static AdminAccountOverview.OperatorRecord operator(String id, String name, String role, String status) {
        return new AdminAccountOverview.OperatorRecord(
                id,
                name,
                name.toLowerCase().replace(' ', '.'),
                name.toLowerCase().replace(' ', '.') + "@nexion.io",
                role,
                true,
                status,
                "",
                0,
                "",
                "MAIL_DISPATCHED");
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 6, 27, 0, 0);
    }

    private static final class FakeSupportAgentRepository implements SupportAgentRepository {
        private final Map<Long, SupportAgentProfileRecord> profiles = new LinkedHashMap<>();
        private final List<SupportAgentAssignmentView> assignments = new ArrayList<>();
        private final List<Long> users = List.of(1001L, 1006L);
        private final List<Long> seededAdminIds = new ArrayList<>();
        private long assignmentId = 1L;

        @Override
        public void ensureSchema() {
        }

        @Override
        public List<SupportAgentProfileRecord> listProfiles(List<Long> adminIds) {
            return adminIds.stream().map(profiles::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public Optional<SupportAgentProfileRecord> findProfile(Long adminId) {
            return Optional.ofNullable(profiles.get(adminId));
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
            if (!profiles.containsKey(adminId)) {
                seededAdminIds.add(adminId);
                profiles.put(adminId, new SupportAgentProfileRecord(
                        adminId,
                        seatType,
                        position,
                        serviceTypes,
                        tags,
                        maxConcurrent,
                        true,
                        true,
                        false,
                        now.toString()));
            }
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
            profiles.put(adminId, new SupportAgentProfileRecord(
                    adminId,
                    seatType,
                    position,
                    serviceTypes,
                    tags,
                    maxConcurrent,
                    enabled,
                    transferable,
                    busy,
                    now.toString()));
        }

        @Override
        public long countActiveAssignments(Long agentAdminId) {
            return assignments.stream()
                    .filter(row -> row.agentAdminId().equals(agentAdminId) && "ACTIVE".equals(row.status()))
                    .count();
        }

        @Override
        public boolean userExists(Long userId) {
            return users.contains(userId);
        }

        @Override
        public List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds) {
            return assignments.stream()
                    .filter(row -> agentAdminIds.contains(row.agentAdminId()) && "ACTIVE".equals(row.status()))
                    .toList();
        }

        @Override
        public SupportAgentAssignmentView upsertAssignment(
                Long agentAdminId,
                Long userId,
                String operator,
                String reason,
                LocalDateTime now) {
            assignments.removeIf(row -> row.userId().equals(userId)
                    && "ACTIVE".equals(row.status()));
            SupportAgentAssignmentView row = new SupportAgentAssignmentView(
                    assignmentId++,
                    agentAdminId,
                    userId,
                    "U" + String.format("%08d", userId),
                    "用户" + userId,
                    "ACTIVE",
                    now.toString(),
                    null,
                    operator,
                    reason,
                    now.toString());
            assignments.add(row);
            return row;
        }

        @Override
        public Optional<SupportAgentAssignmentView> deactivateAssignment(
                Long agentAdminId,
                Long assignmentId,
                String operator,
                String reason,
                LocalDateTime now) {
            for (int index = 0; index < assignments.size(); index += 1) {
                SupportAgentAssignmentView row = assignments.get(index);
                if (row.id().equals(assignmentId) && row.agentAdminId().equals(agentAdminId)) {
                    SupportAgentAssignmentView removed = new SupportAgentAssignmentView(
                            row.id(),
                            row.agentAdminId(),
                            row.userId(),
                            row.userNo(),
                            row.nickname(),
                            "INACTIVE",
                            row.startsAt(),
                            now.toString(),
                            operator,
                            reason,
                            now.toString());
                    assignments.set(index, removed);
                    return Optional.of(removed);
                }
            }
            return Optional.empty();
        }
    }
}
