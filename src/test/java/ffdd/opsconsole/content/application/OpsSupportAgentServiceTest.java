package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.domain.SupportAgentOverview;
import ffdd.opsconsole.content.domain.SupportAgentPageView;
import ffdd.opsconsole.content.domain.SupportAgentProfileRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorScope;
import ffdd.opsconsole.content.domain.SupportTicketAssigneeCandidateView;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.dto.SupportAgentAssignmentRequest;
import ffdd.opsconsole.content.dto.SupportAgentBatchAssignmentRequest;
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
import ffdd.opsconsole.shared.exception.BizException;
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
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsSupportAgentServiceTest {
    private final SupportAgentRepository repository = new FakeSupportAgentRepository();
    private final OpsAdminAccountService accountService = mock(OpsAdminAccountService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final SupportBindingService binding=mock(SupportBindingService.class);
    private final SupportGroupService groups=mock(SupportGroupService.class);
    private final SupportOwnershipService ownership = ffdd.opsconsole.content.SupportTestDependencies.ownership();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsSupportAgentService service = new OpsSupportAgentService(
            repository,
            accountService,
            auditLogService,
            idempotencyService,
            OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            clock, ownership, binding, groups);

    @BeforeEach
    void setUp() {
        when(groups.supervisorQualification(any())).thenReturn(null);
        ((FakeSupportAgentRepository) repository).reset();
        // The default fixture is root; individual non-root tests declare their own scope.
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(1L,ReadMode.ALL,null,null));
        when(ownership.canReadAgent(any(),any())).thenReturn(true);
        when(ownership.supervisor(any())).thenAnswer(call -> {
            Long id=call.getArgument(0);
            return id==1L || ((FakeSupportAgentRepository) repository).findProfile(id)
                    .filter(p -> "MANAGER".equals(p.seatType()) && Boolean.TRUE.equals(p.enabled())).isPresent();
        });
        when(binding.transferLegacy(anyString(),any())).thenAnswer(invocation->{
            ffdd.opsconsole.content.dto.SupportBindingRequest r=invocation.getArgument(1);
            return ApiResult.ok(r.customers().stream().map(c->new SupportAgentAssignmentView(7L,r.targetAgentAdminId(),c.id(),"U-"+c.id(),"Customer","ACTIVE",null,null,"actor",r.reason(),null)).toList());
        });
        when(binding.transferLegacySingle(anyString(),any())).thenAnswer(invocation->{
            ffdd.opsconsole.content.dto.SupportBindingRequest r=invocation.getArgument(1);var c=r.customers().get(0);
            return ApiResult.ok(new SupportAgentAssignmentView(7L,r.targetAgentAdminId(),c.id(),"U-"+c.id(),"Customer","ACTIVE",null,null,"actor",r.reason(),null));
        });
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .executeRetained(anyString(), anyString(), anyString(), any(), any());
        when(accountService.overview()).thenReturn(ApiResult.ok(adminOverview(List.of(
                operator("1", "Root Admin", "super", "enabled"),
                operator("2", "Support Agent", "support", "enabled"),
                operator("3", "Disabled Support", "support", "disabled"),
                operator("4", "Finance Agent", "finance", "enabled")))));
        when(accountService.currentOperator()).thenReturn(Optional.of(operator("1", "Root Admin", "super", "enabled")));
    }

    @Test
    void m5ContentGateAllowsOnlyContentOrSupportSupervisorBesidesSuperAdmin() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "MANAGER", "客服主管", List.of("support"), List.of(), 12, true, true, false, now());

        when(accountService.currentOperator()).thenReturn(Optional.of(operator("5", "Content", "content", "enabled")));
        assertThat(service.canManageM5Content()).isTrue();

        when(accountService.currentOperator()).thenReturn(Optional.of(operator("2", "Support Agent", "support", "enabled")));
        assertThat(service.canManageM5Content()).isTrue();

        when(accountService.currentOperator()).thenReturn(Optional.of(operator("4", "Finance Agent", "finance", "enabled")));
        assertThat(service.canManageM5Content()).isFalse();
    }

    @Test
    void overviewReadsExistingSupportProfilesOnly() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.operators.add(operator("2", "Support Agent", "support", "enabled"));
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());

        ApiResult<SupportAgentOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().agents()).extracting("adminId").containsExactly(2L);
        assertThat(result.getData().agents().get(0).seatType()).isEqualTo("GENERAL");
        assertThat(result.getData().agents().get(0).position()).isEqualTo("专属客服");
        assertThat(result.getData().transferTargets())
                .anySatisfy(target -> assertThat(target).containsEntry("targetType", "agent").containsEntry("targetId", "2"));
        assertThat(result.getData().sources()).contains("nx_admin", "nx_support_agent_profile", "nx_support_agent_user_assignment");
        assertThat(fake.seededAdminIds).isEmpty();
    }

    @Test
    void publicDirectoryUsesOneDedicatedServiceTypeWithoutChangingHistoricalAliases() {
        var fake = (FakeSupportAgentRepository) repository;
        fake.operators.addAll(List.of(operator("2","Legacy Support","support","enabled"),
                operator("5","Legacy Advisor","support","enabled"),operator("6","Manager","support","enabled")));
        fake.updateProfile(2L,"GENERAL","通用客服",List.of("support"),List.of(),12,true,true,false,now());
        fake.updateProfile(5L,"DEDICATED","专属顾问",List.of("advisor"),List.of(),12,true,true,false,now());
        fake.updateProfile(6L,"MANAGER","客服主管",List.of("support","advisor"),List.of(),12,true,true,false,now());

        var overview = service.overview().getData();
        var page = service.agents(new SupportAgentQueryRequest(1L,20L)).getData();
        assertThat(overview.serviceTypes()).containsExactly("support");
        assertThat(page.serviceTypes()).containsExactly("support");
        assertThat(overview.positions()).containsExactly("客服主管","专属客服");
        assertThat(page.positions()).isEqualTo(overview.positions());
        assertThat(overview.agents()).allSatisfy(agent -> assertThat(agent.serviceTypes()).containsExactly("support"));
        assertThat(page.records()).allSatisfy(agent -> assertThat(agent.serviceTypes()).containsExactly("support"));
        assertThat(page.records()).extracting(agent -> agent.position()).containsExactly("专属客服","专属客服","客服主管");
        assertThat(service.transferTargets()).allSatisfy(target ->
                assertThat(target.get("serviceTypes")).isEqualTo(List.of("support")));
        assertThat(overview.transferTargets()).allSatisfy(target ->
                assertThat(target.get("position")).isIn("专属客服","客服主管"));
        assertThat(fake.findProfile(2L).orElseThrow().position()).isEqualTo("通用客服");
        assertThat(fake.findProfile(5L).orElseThrow().serviceTypes()).containsExactly("advisor");
        assertThat(fake.findProfile(6L).orElseThrow().serviceTypes()).containsExactly("support","advisor");
        // A public canonical label does not turn an old advisor-only profile into a legacy routing grant.
        when(accountService.overview()).thenReturn(ApiResult.ok(adminOverview(List.copyOf(fake.operators))));
        assertThat(service.assignableSupportAgent(5L)).isEmpty();
    }

    @Test
    void oldAdvisorAliasRequestKeepsStorageCompatibilityAndReturnsCanonicalType() {
        var fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L,"DEDICATED","专属顾问",List.of("advisor"),List.of(),12,true,true,false,now());
        var request = new SupportAgentProfileUpdateRequest("通用客服",List.of(" advisor ","SUPPORT"),List.of(),
                12,true,true,false,"superadmin","调整客服接派单配置");

        var result = service.updateProfile(2L,"legacy-service-alias",request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().seatType()).isEqualTo("DEDICATED");
        assertThat(result.getData().position()).isEqualTo("专属客服");
        assertThat(result.getData().serviceTypes()).containsExactly("support");
        assertThat(fake.findProfile(2L).orElseThrow().serviceTypes()).containsExactly("advisor","support");
    }

    @Test
    void m1AvailabilitySharesProfileVersionWithoutOverwritingSeatFields() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.operators.add(operator("2", "Support Agent", "support", "enabled"));
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of("keep-tag"),
                17, true, true, false, now());
        var before = fake.findProfile(2L).orElseThrow();
        service.updateAvailabilityForLoad(Map.of("2",
                new ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest(8, true, before.version())));
        var paused = fake.findProfile(2L).orElseThrow();
        assertThat(paused.busy()).isTrue();
        assertThat(paused.tags()).containsExactly("keep-tag");
        assertThat(paused.maxConcurrent()).isEqualTo(17);
        assertThat(paused.seatType()).isEqualTo(before.seatType());
        assertThat(paused.version()).isEqualTo(before.version() + 1);
        assertThat(service.availabilityStates().get("2")).containsEntry("busy", true)
                .containsEntry("profileVersion", paused.version());
        assertThat(service.transferTargets()).noneMatch(row -> "2".equals(row.get("targetId")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateAvailabilityForLoad(Map.of("2",
                new ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest(8, false, before.version()))))
                .hasMessageContaining("SUPPORT_AGENT_PROFILE_VERSION_CONFLICT");
        assertThat(fake.findProfile(2L).orElseThrow().busy()).isTrue();
        service.updateAvailabilityForLoad(Map.of("2",
                new ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest(8, false, paused.version())));
        assertThat(service.transferTargets()).anyMatch(row -> "2".equals(row.get("targetId")));
    }

    @Test
    void m1AvailabilityRejectsMissingVersionAndUnauthorizedActorBeforeWrites() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateAvailabilityForLoad(Map.of("2",
                new ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest(8, true))))
                .hasMessageContaining("SUPPORT_AGENT_PROFILE_EXPECTED_VERSION_REQUIRED");
        when(accountService.currentOperator()).thenReturn(Optional.of(operator("4", "Finance Agent", "finance", "enabled")));
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(4L,ReadMode.PERSONAL,null,null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateAvailabilityForLoad(Map.of("2",
                new ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest(8, true, 1L))))
                .hasMessageContaining("SUPPORT_LOAD_MANAGEMENT_FORBIDDEN");
        assertThat(fake.findProfile(2L).orElseThrow().busy()).isFalse();
        assertThat(fake.findProfile(2L).orElseThrow().version()).isEqualTo(1L);
    }

    @Test
    void pausedAgentRemainsVisibleButCannotReceiveConversationTransfersUntilResumed() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.operators.add(operator("2", "Support Agent", "support", "enabled"));
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(),
                12, true, true, true, now());
        assertThat(service.overview().getData().agents()).extracting("adminId").contains(2L);
        assertThat(service.transferTargets()).noneMatch(target -> "2".equals(target.get("targetId")));
        assertThat(service.overview().getData().transferTargets())
                .noneMatch(target -> "2".equals(target.get("targetId")));
        assertThat(service.assignableSupportAgent(2L)).isEmpty();
        when(accountService.currentOperator()).thenReturn(Optional.of(operator("2", "Support Agent", "support", "enabled")));
        assertThat(service.currentAssignableSupportAgent()).isEmpty();
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(),
                12, true, true, false, now());
        assertThat(service.transferTargets()).anyMatch(target -> "2".equals(target.get("targetId")));
        assertThat(service.assignableSupportAgent(2L)).isPresent();
        assertThat(service.currentAssignableSupportAgent()).isPresent();
    }

    @Test
    void retiredTicketAssignmentReturnsNoIndependentCandidates() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.ticketAssigneeCandidates.add(new SupportTicketAssigneeCandidateView(2L, "Available Support"));

        ApiResult<List<SupportTicketAssigneeCandidateView>> result = service.ticketAssigneeCandidates();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEmpty();
        assertThat(fake.seededAdminIds).isEmpty();
        assertThat(fake.ensureSchemaCalls).isZero();
        assertThat(fake.profiles).isEmpty();
        verifyNoInteractions(accountService);
    }

    @Test
    void managedDirectoryAndAvailabilityExcludeOtherGroupAndKeepDisabledMember() {
        FakeSupportAgentRepository fake=(FakeSupportAgentRepository)repository;
        fake.operators.addAll(List.of(operator("2","Paused member","support","disabled"),
                operator("7","Other group","support","enabled"),operator("6","Dual role outside own group","support","enabled")));
        fake.agentGroups.putAll(Map.of(2L,10L,7L,20L,6L,20L));fake.groupOwners.putAll(Map.of(10L,6L,20L,8L));
        fake.updateProfile(2L,"GENERAL","通用客服",List.of("support"),List.of(),12,false,false,false,now());
        fake.updateProfile(7L,"GENERAL","通用客服",List.of("support"),List.of(),12,true,true,false,now());
        ReadScope managed=new ReadScope(6L,ReadMode.MANAGED,null,null);
        when(ownership.defaultQueryScope(null,null)).thenReturn(managed);
        var page=service.agents(new SupportAgentQueryRequest(1L,20L)).getData();
        assertThat(page.total()).isEqualTo(1);assertThat(page.records()).extracting("adminId").containsExactly(2L);
        assertThat(page.records().get(0).status()).isEqualTo("disabled");
        assertThat(service.availabilityStates()).containsOnlyKeys("2");
        assertThat(service.transferTargets()).isEmpty();
        assertThat(fake.lastCountRoleScope.readScope()).isEqualTo(managed);
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(9L,ReadMode.MANAGED,null,null));
        assertThat(service.agents(new SupportAgentQueryRequest(1L,20L)).getData().total()).isZero();
        assertThat(service.availabilityStates()).isEmpty();
    }

    @Test
    void disabledAccountWithEnabledProfileKeepsAssetsButLeavesBothTransferProjections() {
        var fake = (FakeSupportAgentRepository) repository;
        fake.operators.add(operator("3", "Disabled member", "support", "disabled"));
        fake.updateProfile(3L, "DEDICATED", "专属客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.assignments.add(new SupportAgentAssignmentView(91L, 3L, 1001L, "U1001", "Customer", "ACTIVE", null, null, "actor", "reason", null));
        var overview = service.overview().getData();
        assertThat(overview.agents()).extracting("adminId").containsExactly(3L);
        assertThat(overview.agents().get(0).enabled()).isTrue();
        assertThat(overview.advisorAssignments()).extracting("id").containsExactly(91L);
        assertThat(service.agents(new SupportAgentQueryRequest(1L, 20L)).getData().records()).extracting("adminId").containsExactly(3L);
        assertThat(service.availabilityStates()).containsOnlyKeys("3");
        assertThat(overview.transferTargets()).isEmpty();
        assertThat(service.transferTargets()).isEmpty();
    }

    @Test
    void assignmentAuthorityKeepsUnavailableRosterAndScopesCandidatesWithoutAddingBusyPolicy() {
        var fake = (FakeSupportAgentRepository) repository;
        for (long id : List.of(2L, 5L, 6L, 7L, 8L, 9L)) {
            fake.operators.add(operator(String.valueOf(id), "Member " + id, "support", "enabled"));
            fake.agentGroups.put(id, id == 9L ? 20L : 10L);
            fake.updateProfile(id, id == 6L ? "MANAGER" : "DEDICATED", "专属客服", List.of("support"), List.of(), 12, true, true, id == 8L, now());
        }
        // Mapper authority: removed SERVICE, pure supervisor, and conflicting SERVICE intervals are ineligible.
        fake.serviceQualificationCounts.putAll(Map.of(5L, 0, 6L, 0, 7L, 2));
        fake.groupOwners.putAll(Map.of(10L, 11L, 20L, 12L));
        var scope = new ReadScope(11L, ReadMode.MANAGED, null, null);
        when(ownership.defaultQueryScope(null, null)).thenReturn(scope);
        var overview = service.overview().getData();
        assertThat(overview.agents()).extracting("adminId").containsExactly(2L, 5L, 6L, 7L, 8L);
        assertThat(overview.agents().stream().filter(agent -> Boolean.TRUE.equals(agent.assignmentEligible())).map(agent -> agent.adminId()))
                .containsExactly(2L, 8L);
        assertThat(overview.transferTargets()).extracting(row -> row.get("targetId")).containsExactly("2");
        assertThat(service.transferTargets()).extracting(row -> row.get("targetId")).containsExactly("2");
        assertThat(service.availabilityStates()).containsOnlyKeys("2", "5", "6", "7", "8");
        assertThat(fake.eligibilityReadIds).allSatisfy(ids -> assertThat(ids).doesNotContain(9L));
        assertThat(fake.lastEligibilityScope).isEqualTo(scope);
    }

    @Test
    void agentsReturnPagedBackendRowsAndCurrentPageAssignments() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.operators.addAll(List.of(
                operator("2", "Support Agent A", "support", "enabled"),
                operator("5", "Support Agent B", "support", "enabled"),
                operator("6", "Support Agent C", "support", "enabled"),
                operator("7", "Support Agent D", "support", "enabled"),
                operator("8", "Disabled Support", "support", "disabled")));
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.updateProfile(5L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.updateProfile(6L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.updateProfile(7L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.assignments.add(new SupportAgentAssignmentView(10L, 2L, 1001L, "U00001001", "用户1001", "ACTIVE", now().toString(), null, "system", "seed", now().toString()));
        fake.assignments.add(new SupportAgentAssignmentView(11L, 6L, 1006L, "U00001006", "用户1006", "ACTIVE", now().toString(), null, "system", "seed", now().toString()));

        ApiResult<SupportAgentPageView> result = service.agents(new SupportAgentQueryRequest(2L, 2L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().total()).isEqualTo(5);
        assertThat(result.getData().pageNum()).isEqualTo(2);
        assertThat(result.getData().pageSize()).isEqualTo(2);
        assertThat(result.getData().records()).extracting("adminId").containsExactly(6L, 7L);
        assertThat(result.getData().advisorAssignments()).extracting(SupportAgentAssignmentView::agentAdminId).containsExactly(6L);
        assertThat(fake.defaultProfileAttempts).isEmpty();
        assertThat(fake.profileReadIds).containsExactly(List.of(6L, 7L));
        assertThat(fake.assignmentCountIds).containsExactly(6L, 7L);
        assertThat(fake.assignmentReadIds).containsExactly(List.of(6L, 7L));
        assertThat(fake.countOperatorCalls).isEqualTo(1);
        assertThat(fake.pageOperatorCalls).isEqualTo(1);
        assertThat(fake.roleScopeCalls).isEqualTo(1);
        assertThat(fake.lastPageRoleScope).isSameAs(fake.lastCountRoleScope);
        assertThat(fake.lastOffset).isEqualTo(2L);
        assertThat(fake.lastLimit).isEqualTo(2L);
        verifyNoInteractions(accountService);
    }

    @Test
    void agentPageCostDependsOnPageSizeRatherThanAllAdministratorFixtures() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        for (long id = 1; id <= 10_000; id++) {
            fake.operators.add(operator(String.valueOf(id), "Support " + id, "support", "enabled"));
            fake.updateProfile(id, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
            fake.operators.add(operator(String.valueOf(id + 10_000), "Disabled " + id, "support", "disabled"));
            fake.operators.add(operator(String.valueOf(id + 20_000), "Finance " + id, "finance", "enabled"));
        }

        var page = service.agents(new SupportAgentQueryRequest(1L, 5L)).getData();

        assertThat(page.total()).isEqualTo(20_000);
        assertThat(page.records()).extracting("adminId").containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(fake.countOperatorCalls).isEqualTo(1);
        assertThat(fake.pageOperatorCalls).isEqualTo(1);
        assertThat(fake.profileReadIds).containsExactly(List.of(1L, 2L, 3L, 4L, 5L));
        assertThat(fake.assignmentCountIds).hasSize(5);
        assertThat(fake.defaultProfileAttempts).isEmpty();
        verifyNoInteractions(accountService);
    }

    @Test
    void agentPageMaterializesOnlyMissingPageProfilesAndKeepsPausedProfileAndAvatar() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        for (long id : List.of(2L, 5L, 6L, 7L)) {
            fake.operators.add(operator(String.valueOf(id), "Support " + id, "support", "enabled"));
        }
        fake.avatars.put(7L, "page-avatar");
        fake.avatarVersions.put(7L, 19L);
        fake.updateProfile(7L, "MANAGER", "客服主管", List.of("support"), List.of("existing"),
                17, false, false, true, now());
        SupportAgentProfileRecord before = fake.findProfile(7L).orElseThrow();

        var page = service.agents(new SupportAgentQueryRequest(2L, 2L)).getData();

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.records()).extracting("adminId").containsExactly(6L, 7L);
        assertThat(fake.defaultProfileAttempts).containsExactly(6L);
        assertThat(fake.seededAdminIds).containsExactly(6L);
        assertThat(fake.profiles).doesNotContainKeys(2L, 5L);
        assertThat(fake.profileReadIds).containsExactly(List.of(6L, 7L), List.of(6L));
        assertThat(fake.findProfile(7L)).contains(before);
        assertThat(page.records().get(1).enabled()).isFalse();
        assertThat(page.records().get(1).busy()).isTrue();
        assertThat(page.records().get(1).avatarAssetId()).isEqualTo("page-avatar");
        assertThat(page.records().get(1).avatarVersion()).isEqualTo(19L);
        assertThat(page.records().get(1).version()).isEqualTo(before.version());
        verifyNoInteractions(accountService);
    }

    @Test
    void ordinaryAgentPageCountsAndSelectsOnlyTheAuthenticatedAgent() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.operators.addAll(List.of(operator("6", "Self", "support", "enabled"),
                operator("7", "Other", "support", "enabled")));
        when(ownership.actorId()).thenReturn(6L);
        when(ownership.supervisor(6L)).thenReturn(false);
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(6L,ReadMode.PERSONAL,null,null));

        var page = service.agents(new SupportAgentQueryRequest(1L, 5L)).getData();

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).extracting("adminId").containsExactly(6L);
        assertThat(fake.lastCountScope).isEqualTo(6L);
        assertThat(fake.lastPageScope).isEqualTo(6L);
        assertThat(fake.defaultProfileAttempts).containsExactly(6L);
        verify(ownership).defaultQueryScope(null,null);
        verifyNoInteractions(accountService);
    }

    @Test
    void unavailableAgentIsRejectedBeforePageQueriesOrDefaultMaterialization() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        when(ownership.actorId()).thenReturn(6L);
        when(ownership.supervisor(6L)).thenReturn(false);
        when(ownership.defaultQueryScope(null,null)).thenThrow(new BizException(403, "SUPPORT_AGENT_UNAVAILABLE"));

        assertThatThrownBy(() -> service.agents(new SupportAgentQueryRequest(1L, 5L)))
                .isInstanceOf(BizException.class).hasMessage("SUPPORT_AGENT_UNAVAILABLE");

        assertThat(fake.ensureSchemaCalls).isZero();
        assertThat(fake.countOperatorCalls).isZero();
        assertThat(fake.pageOperatorCalls).isZero();
        assertThat(fake.defaultProfileAttempts).isEmpty();
        verifyNoInteractions(accountService);
    }

    @Test
    void agentPageNormalizesSizeAndReturnsEmptyForLastPageAndOverflowingPageNumber() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        for (long id = 1; id <= 105; id++) {
            fake.operators.add(operator(String.valueOf(id), "Support " + id, "support", "enabled"));
        }
        assertThat(service.agents(new SupportAgentQueryRequest(0L, 0L)).getData().pageSize()).isEqualTo(10);
        var last = service.agents(new SupportAgentQueryRequest(2L, 1_000L)).getData();
        assertThat(last.pageSize()).isEqualTo(100);
        assertThat(last.total()).isEqualTo(105);
        assertThat(last.records()).extracting("adminId").containsExactly(101L, 102L, 103L, 104L, 105L);
        int pageCalls = fake.pageOperatorCalls;
        int profileReads = fake.profileReadIds.size();
        int defaults = fake.defaultProfileAttempts.size();

        var beyond = service.agents(new SupportAgentQueryRequest(Long.MAX_VALUE, 100L)).getData();

        assertThat(beyond.total()).isEqualTo(105);
        assertThat(beyond.records()).isEmpty();
        assertThat(beyond.advisorAssignments()).isEmpty();
        assertThat(fake.pageOperatorCalls).isEqualTo(pageCalls);
        assertThat(fake.profileReadIds).hasSize(profileReads);
        assertThat(fake.defaultProfileAttempts).hasSize(defaults);
        verifyNoInteractions(accountService);
    }

    @Test
    void profileReplayRechecksCurrentTargetBeforeRetainedReceipt() {
        when(ownership.canReadAgent(1L, 2L)).thenReturn(false);
        var request = new SupportAgentProfileUpdateRequest("通用客服", List.of("support"), List.of(),
                12, true, true, false, "superadmin", "调整客服接派单配置");

        var result = service.updateProfile(2L, "old-profile-receipt", request);

        assertThat(result.getCode()).isEqualTo(403);
        verifyNoInteractions(idempotencyService);
        verify(ownership).lockAgent(1L);
        verify(ownership).lockAgent(2L);
    }

    @Test
    void updateProfileRequiresStructuredFieldsAndAudits() {
        SupportAgentProfileUpdateRequest request = new SupportAgentProfileUpdateRequest(
                "通用客服",
                List.of("support"),
                List.of("高价值用户", "账户安全"),
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
        assertThat(result.getData().position()).isEqualTo("专属客服");
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
        assertThat(result.getData().serviceTypes()).containsExactly("support");
        assertThat(fake.findProfile(2L).orElseThrow().seatType()).isEqualTo("DEDICATED");
        assertThat(fake.findProfile(2L).orElseThrow().serviceTypes()).contains("advisor");
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
        org.mockito.Mockito.doThrow(new ffdd.opsconsole.shared.exception.BizException(422,"BINDING_REJECTED")).when(binding).transferLegacySingle(anyString(),any());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.assignAdvisorUser(2L,"adapter-invalid-key",
                new SupportAgentAssignmentRequest(1001L,"spoofed","validated by binding",7L,1L)))
            .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("BINDING_REJECTED");
        assertThat(((FakeSupportAgentRepository)repository).assignments).isEmpty();
    }

    @Test
    void assignSeatWritesDedicatedProfileAndUserBinding() {
        var customers=List.of(new ffdd.opsconsole.content.dto.SupportBindingRequest.Customer(1001L,null,1L));
        var request=new SupportAgentSeatAssignmentRequest("专属客服",List.of("advisor"),List.of(),30,true,true,false,List.of(1001L),1L,"actor","formal seat assignment",customers);
        assertThat(service.assignSeat(2L,"seat-adapter-key",request).getCode()).isZero();
        var lockOrder = org.mockito.Mockito.inOrder(ownership, binding);
        lockOrder.verify(ownership).lockCustomer(1001L);
        lockOrder.verify(binding).prepareTransferLocks(new ffdd.opsconsole.content.dto.SupportBindingRequest(2L,customers,"formal seat assignment"));
        lockOrder.verify(ownership).lockAgent(1L);
        lockOrder.verify(ownership).lockAgent(2L);
        verify(binding).transferInTransaction("seat-adapter-key",new ffdd.opsconsole.content.dto.SupportBindingRequest(2L,customers,"formal seat assignment"));
        org.mockito.Mockito.verify(binding,org.mockito.Mockito.never()).transfer(anyString(),any());
    }

    @Test void seatAssignmentRejectsUnprovableCompleteLockSetBeforeTargetMutation() {
        var customers=List.of(new ffdd.opsconsole.content.dto.SupportBindingRequest.Customer(1001L,null,1L));
        var transfer=new ffdd.opsconsole.content.dto.SupportBindingRequest(2L,customers,"formal seat assignment");
        org.mockito.Mockito.doThrow(new BizException(404,"SUPPORT_AGENT_NOT_FOUND"))
                .when(binding).prepareTransferLocks(transfer);
        var request=new SupportAgentSeatAssignmentRequest("专属客服",List.of("advisor"),List.of(),30,true,true,false,
                List.of(1001L),1L,"actor","formal seat assignment",customers);
        assertThatThrownBy(()->service.assignSeat(2L,"unprovable-lock-set",request)).isInstanceOf(BizException.class);
        org.mockito.Mockito.verify(ownership,org.mockito.Mockito.never()).lockAgent(any());
        verifyNoInteractions(idempotencyService);
        org.mockito.Mockito.verify(binding,org.mockito.Mockito.never()).transferInTransaction(anyString(),any());
    }

    @Test
    void assignSeatChangingOnlyPositionPreservesOmittedProfileFields() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(
                2L,
                "GENERAL",
                "通用客服",
                List.of("support"),
                List.of("账户安全", "提现", "账户"),
                17,
                false,
                false,
                true,
                now());

        var result = service.assignSeat(
                2L,
                "idem-seat-position-only",
                new SupportAgentSeatAssignmentRequest(
                        "客服主管",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        "superadmin",
                        "仅调整客服坐席岗位"));

        assertThat(result.getCode()).isZero();
        SupportAgentProfileRecord profile = fake.findProfile(2L).orElseThrow();
        assertThat(profile.position()).isEqualTo("客服主管");
        assertThat(profile.serviceTypes()).containsExactly("support");
        assertThat(profile.tags()).containsExactly("账户安全", "提现", "账户");
        assertThat(profile.maxConcurrent()).isEqualTo(17);
        assertThat(profile.enabled()).isFalse();
        assertThat(profile.transferable()).isFalse();
        assertThat(profile.busy()).isTrue();
    }

    @Test
    void assignSeatExplicitEmptyTagsClearsTags() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(
                2L,
                "GENERAL",
                "通用客服",
                List.of("support"),
                List.of("账户安全", "提现", "账户"),
                17,
                true,
                true,
                false,
                now());

        var result = service.assignSeat(
                2L,
                "idem-seat-clear-tags",
                new SupportAgentSeatAssignmentRequest(
                        "通用客服",
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        "superadmin",
                        "清空客服技能标签"));

        assertThat(result.getCode()).isZero();
        assertThat(fake.findProfile(2L).orElseThrow().tags()).isEmpty();
    }

    @Test
    void assignSeatRejectsNonSupervisorActor() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        when(ownership.actorId()).thenReturn(2L);
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(2L,ReadMode.PERSONAL,null,null));
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
                        1L,
                        "support.agent",
                        "尝试分配客服坐席",
                        List.of(new ffdd.opsconsole.content.dto.SupportBindingRequest.Customer(1001L,null,1L))));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_SEAT_ASSIGNMENT_FORBIDDEN");
    }

    @Test
    void assignAdvisorReplacesExistingActiveBindingForSameUser() {
        var fake=(FakeSupportAgentRepository)repository;
        fake.upsertAssignment(2L,1001L,"actor","fixture baseline",now());
        var request=new SupportAgentAssignmentRequest(1001L,"spoofed","formal transfer adapter",7L,1L);
        assertThat(service.assignAdvisorUser(2L,"adapter-single-key",request).getCode()).isZero();
        verify(binding).transferLegacySingle("adapter-single-key",new ffdd.opsconsole.content.dto.SupportBindingRequest(2L,
            List.of(new ffdd.opsconsole.content.dto.SupportBindingRequest.Customer(1001L,7L,1L)),"formal transfer adapter"));
    }

    @Test
    void assignAdvisorRejectsNonSupervisorActor() {
        org.mockito.Mockito.doThrow(new ffdd.opsconsole.shared.exception.BizException(403,"BINDING_REJECTED")).when(binding).transferLegacySingle(anyString(),any());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.assignAdvisorUser(2L,"adapter-invalid-key",
                new SupportAgentAssignmentRequest(1001L,"spoofed","validated by binding",7L,1L)))
            .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("BINDING_REJECTED");
        assertThat(((FakeSupportAgentRepository)repository).assignments).isEmpty();
    }

    @Test
    void assignAdvisorRejectsMissingBusinessUser() {
        org.mockito.Mockito.doThrow(new ffdd.opsconsole.shared.exception.BizException(404,"BINDING_REJECTED")).when(binding).transferLegacySingle(anyString(),any());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.assignAdvisorUser(2L,"adapter-invalid-key",
                new SupportAgentAssignmentRequest(1001L,"spoofed","validated by binding",7L,1L)))
            .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("BINDING_REJECTED");
        assertThat(((FakeSupportAgentRepository)repository).assignments).isEmpty();
    }

    @Test
    void batchAdvisorAssignmentValidatesEveryUserBeforeWritingAnything() {
        var result=service.assignAdvisorUsers(2L,"missing-expectation-key",new SupportAgentBatchAssignmentRequest(List.of(1001L),"actor","missing binding snapshot"));
        assertThat(result.getCode()).isEqualTo(422);
        verifyNoInteractions(binding);
        assertThat(((FakeSupportAgentRepository)repository).assignments).isEmpty();
    }

    @Test
    void batchAdvisorAssignmentWritesEveryUserThroughOneIdempotentCommand() {
        var ids=LongStream.rangeClosed(1001L,1002L).boxed().toList();
        var customers=ids.stream().map(id->new ffdd.opsconsole.content.dto.SupportBindingRequest.Customer(id,null,1L)).toList();
        var request=new SupportAgentBatchAssignmentRequest(ids,"actor","explicit snapshot adapter",customers);
        assertThat(service.assignAdvisorUsers(2L,"adapter-batch-key",request).getCode()).isZero();
        verify(binding).transferLegacy("adapter-batch-key",new ffdd.opsconsole.content.dto.SupportBindingRequest(2L,customers,"explicit snapshot adapter"));
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void batchAdvisorAssignmentRejectsOneHundredAndOneUsersBeforeAnyDatabaseWork() {
        var result=service.assignAdvisorUsers(2L,"missing-expectation-key",new SupportAgentBatchAssignmentRequest(List.of(1001L),"actor","missing binding snapshot"));
        assertThat(result.getCode()).isEqualTo(422);
        verifyNoInteractions(binding);
        assertThat(((FakeSupportAgentRepository)repository).assignments).isEmpty();
    }

    @Test
    void batchAdvisorAssignmentAcceptsExactlyOneHundredUsers() {
        var ids=LongStream.rangeClosed(1001L,1100L).boxed().toList();
        var customers=ids.stream().map(id->new ffdd.opsconsole.content.dto.SupportBindingRequest.Customer(id,null,1L)).toList();
        var request=new SupportAgentBatchAssignmentRequest(ids,"actor","explicit snapshot adapter",customers);
        assertThat(service.assignAdvisorUsers(2L,"adapter-batch-key",request).getCode()).isZero();
        verify(binding).transferLegacy("adapter-batch-key",new ffdd.opsconsole.content.dto.SupportBindingRequest(2L,customers,"explicit snapshot adapter"));
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void profileUpdateRejectsAStaleVersionBeforeMutation() {
        FakeSupportAgentRepository fake = (FakeSupportAgentRepository) repository;
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        fake.updateProfile(2L, "GENERAL", "通用客服", List.of("support"), List.of(), 12, true, true, false, now());
        long currentVersion = fake.findProfile(2L).orElseThrow().version();

        var result = service.updateProfile(
                2L,
                "idem-profile-stale",
                new SupportAgentProfileUpdateRequest(
                        "通用客服",
                        List.of("support"),
                        List.of("夜班"),
                        14,
                        true,
                        true,
                        false,
                        currentVersion - 1,
                        "superadmin",
                        "并发调整客服接派单配置"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(fake.findProfile(2L).orElseThrow().tags()).isEmpty();
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
        @Override
        public Optional<ffdd.opsconsole.content.domain.AppSupportAdvisorView> findAppAdvisor(Long userId) {
            throw new UnsupportedOperationException("App advisor projection is exercised by real MySQL tests");
        }
        private final Map<Long, SupportAgentProfileRecord> profiles = new LinkedHashMap<>();
        private final List<SupportAgentAssignmentView> assignments = new ArrayList<>();
        private final List<Long> users = LongStream.rangeClosed(1001L, 1101L).boxed().toList();
        private final List<Long> seededAdminIds = new ArrayList<>();
        private final List<SupportTicketAssigneeCandidateView> ticketAssigneeCandidates = new ArrayList<>();
        private final List<AdminAccountOverview.OperatorRecord> operators = new ArrayList<>();
        private final Map<Long, Long> agentGroups = new LinkedHashMap<>();
        private final Map<Long, Long> groupOwners = new LinkedHashMap<>();
        private final Map<Long, String> avatars = new LinkedHashMap<>();
        private final Map<Long, Long> avatarVersions = new LinkedHashMap<>();
        private final Map<Long, Integer> serviceQualificationCounts = new LinkedHashMap<>();
        private final List<List<Long>> eligibilityReadIds = new ArrayList<>();
        private ReadScope lastEligibilityScope;
        private final List<Long> defaultProfileAttempts = new ArrayList<>();
        private final List<List<Long>> profileReadIds = new ArrayList<>();
        private final List<Long> assignmentCountIds = new ArrayList<>();
        private final List<List<Long>> assignmentReadIds = new ArrayList<>();
        private int countOperatorCalls;
        private int pageOperatorCalls;
        private int roleScopeCalls;
        private SupportOperatorScope lastCountRoleScope;
        private SupportOperatorScope lastPageRoleScope;
        private Long lastCountScope;
        private Long lastPageScope;
        private long lastLimit;
        private long lastOffset;
        private int ensureSchemaCalls;
        private long assignmentId = 1L;
        private int userExistsCalls;
        private int upsertAssignmentCalls;
        private int bulkUserLookupCalls;
        private int bulkUpsertAssignmentCalls;

        private void reset() {
            profiles.clear();
            assignments.clear();
            seededAdminIds.clear();
            ticketAssigneeCandidates.clear();
            operators.clear();
            agentGroups.clear();
            groupOwners.clear();
            avatars.clear();
            avatarVersions.clear();
            serviceQualificationCounts.clear();
            eligibilityReadIds.clear();
            lastEligibilityScope = null;
            defaultProfileAttempts.clear();
            profileReadIds.clear();
            assignmentCountIds.clear();
            assignmentReadIds.clear();
            countOperatorCalls = 0;
            pageOperatorCalls = 0;
            roleScopeCalls = 0;
            lastCountRoleScope = null;
            lastPageRoleScope = null;
            lastCountScope = null;
            lastPageScope = null;
            lastLimit = 0;
            lastOffset = 0;
            ensureSchemaCalls = 0;
            assignmentId = 1L;
            userExistsCalls = 0;
            upsertAssignmentCalls = 0;
            bulkUserLookupCalls = 0;
            bulkUpsertAssignmentCalls = 0;
        }

        private void resetDatabaseWorkCounters() {
            ensureSchemaCalls = 0;
            userExistsCalls = 0;
            upsertAssignmentCalls = 0;
            bulkUserLookupCalls = 0;
            bulkUpsertAssignmentCalls = 0;
        }

        private int databaseWorkCalls() {
            return ensureSchemaCalls + userExistsCalls + upsertAssignmentCalls
                    + bulkUserLookupCalls + bulkUpsertAssignmentCalls;
        }

        @Override
        public void ensureSchema() {
            ensureSchemaCalls += 1;
        }

        @Override
        public List<SupportTicketAssigneeCandidateView> listTicketAssigneeCandidates() {
            return List.copyOf(ticketAssigneeCandidates);
        }

        @Override
        public List<SupportAgentProfileRecord> listProfiles(List<Long> adminIds) {
            profileReadIds.add(List.copyOf(adminIds));
            return adminIds.stream().map(profiles::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public SupportOperatorScope supportOperatorScope(Long visibleAdminId) {
            roleScopeCalls++;
            return new SupportOperatorScope(visibleAdminId, List.of(), List.of(), false);
        }

        @Override
        public long countSupportOperators(SupportOperatorScope scope) {
            countOperatorCalls++;
            lastCountRoleScope = scope;
            lastCountScope = scope.visibleAdminId();
            return visibleOperators(scope).size();
        }

        @Override
        public List<SupportOperatorRecord> pageSupportOperators(SupportOperatorScope scope, long limit, long offset) {
            pageOperatorCalls++;
            lastPageRoleScope = scope;
            lastPageScope = scope.visibleAdminId();
            lastLimit = limit;
            lastOffset = offset;
            return visibleOperators(scope).stream().skip(offset).limit(limit)
                    .map(row -> new SupportOperatorRecord(Long.valueOf(row.id()), row.name(), row.email(),
                            avatars.get(Long.valueOf(row.id())), avatarVersions.getOrDefault(Long.valueOf(row.id()), 0L),row.status()))
                    .toList();
        }

        @Override
        public List<Long> listAssignmentEligibleAgentIds(List<Long> directoryIds, ReadScope scope) {
            eligibilityReadIds.add(List.copyOf(directoryIds));
            lastEligibilityScope = scope;
            return directoryIds.stream().filter(id -> visibleAgent(id, scope))
                    .filter(id -> serviceQualificationCounts.getOrDefault(id, 1) == 1)
                    .filter(id -> findProfile(id).filter(profile -> Boolean.TRUE.equals(profile.enabled())).isPresent())
                    .filter(id -> operators.stream().noneMatch(row -> row.id().equals(String.valueOf(id)) && !"enabled".equals(row.status())))
                    .toList();
        }

        private List<AdminAccountOverview.OperatorRecord> visibleOperators(SupportOperatorScope scope) {
            return operators.stream().filter(row -> "support".equalsIgnoreCase(row.role()))
                    .filter(row -> scope.visibleAdminId() == null || scope.visibleAdminId().toString().equals(row.id()))
                    .filter(row -> visibleAgent(Long.valueOf(row.id()),scope.readScope()))
                    .sorted(java.util.Comparator.comparingLong(row -> Long.parseLong(row.id()))).toList();
        }

        private boolean visibleAgent(Long id, ReadScope scope) {
            if(scope==null) return false;
            Long group=agentGroups.get(id);
            if(scope.requestedAgentId()!=null && !scope.requestedAgentId().equals(id)) return false;
            if(scope.requestedGroupId()!=null && !scope.requestedGroupId().equals(group)) return false;
            return switch(scope.mode()) {
                case ALL -> scope.actorId()==1L;
                case PERSONAL -> scope.actorId().equals(id);
                case MANAGED -> group!=null && scope.actorId().equals(groupOwners.get(group));
            };
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
            defaultProfileAttempts.add(adminId);
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
                    profiles.containsKey(adminId) ? profiles.get(adminId).version() + 1 : 1L,
                    now.toString()));
        }

        @Override
        public boolean updateProfileCas(
                Long adminId, String seatType, String position, List<String> serviceTypes, List<String> tags,
                int maxConcurrent, boolean enabled, boolean transferable, boolean busy,
                long expectedVersion, LocalDateTime now) {
            SupportAgentProfileRecord current = profiles.get(adminId);
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            profiles.put(adminId, new SupportAgentProfileRecord(
                    adminId, seatType, position, serviceTypes, tags, maxConcurrent,
                    enabled, transferable, busy, expectedVersion + 1, now.toString()));
            return true;
        }

        @Override
        public long countActiveAssignments(Long agentAdminId) {
            assignmentCountIds.add(agentAdminId);
            return assignments.stream()
                    .filter(row -> row.agentAdminId().equals(agentAdminId) && "ACTIVE".equals(row.status()))
                    .count();
        }

        @Override
        public long countActiveAssignments(Long agentAdminId, ReadScope scope) {
            java.util.Objects.requireNonNull(scope);
            return visibleAgent(agentAdminId,scope)?countActiveAssignments(agentAdminId):0;
        }

        @Override
        public List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds, ReadScope scope) {
            java.util.Objects.requireNonNull(scope);
            return listActiveAssignments(agentAdminIds.stream().filter(id->visibleAgent(id,scope)).toList());
        }

        @Override
        public boolean userExists(Long userId) {
            userExistsCalls += 1;
            return users.contains(userId);
        }

        @Override
        public List<Long> findExistingUserIds(List<Long> userIds) {
            bulkUserLookupCalls += 1;
            return userIds.stream().filter(users::contains).toList();
        }

        @Override
        public List<SupportAgentAssignmentView> listActiveAssignments(List<Long> agentAdminIds) {
            assignmentReadIds.add(List.copyOf(agentAdminIds));
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
            upsertAssignmentCalls += 1;
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
        public List<SupportAgentAssignmentView> upsertAssignments(
                Long agentAdminId,
                List<Long> userIds,
                String operator,
                String reason,
                LocalDateTime now) {
            bulkUpsertAssignmentCalls += 1;
            List<SupportAgentAssignmentView> rows = new ArrayList<>();
            for (Long userId : userIds) {
                assignments.removeIf(row -> row.userId().equals(userId) && "ACTIVE".equals(row.status()));
                SupportAgentAssignmentView row = new SupportAgentAssignmentView(
                        assignmentId++, agentAdminId, userId, "U" + String.format("%08d", userId),
                        "用户" + userId, "ACTIVE", now.toString(), null, operator, reason, now.toString());
                assignments.add(row);
                rows.add(row);
            }
            return rows;
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
