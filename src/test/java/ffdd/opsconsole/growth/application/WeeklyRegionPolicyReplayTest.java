package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.growth.mapper.WeeklyRegionPolicyMapper;
import ffdd.opsconsole.platform.application.*;
import ffdd.opsconsole.platform.domain.*;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real permission guard, dispatcher, H-domain replay and policy; persistence is an isolated fake. */
class WeeklyRegionPolicyReplayTest {
    final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    final WeeklyRegionPolicyMapper mapper = mock(WeeklyRegionPolicyMapper.class);
    final WeeklyQuestSnapshotService snapshots = mock(WeeklyQuestSnapshotService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final AtomicReference<String> stored = new AtomicReference<>();
    final WeeklyRegionPolicyService policy = new WeeklyRegionPolicyService(config,mapper,
            Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"),ZoneOffset.UTC));
    final AuditReplayBusinessPermissionGuard guard = new AuditReplayBusinessPermissionGuard(
            mock(TrustDisclosureRepository.class),mock(AdminOperatorRoleResolver.class),mock(EmergencyControlRepository.class),null,config,policy);
    final OpsGrowthService growth = new OpsGrowthService(config,null,null,null,audit,new ObjectMapper(),null,
            Optional.empty(),Optional.empty(),Optional.empty(),null,null,Optional.empty(),null,null,snapshots,policy);
    final AuditReplayDispatcher dispatcher = new AuditReplayDispatcher(List.of(growth));
    final AuditReplayCommand command = new AuditReplayCommand("H","h3_weekly_region_policy",Map.of(
            "expectedRevision",0,"mappings",List.of(Map.of("callingCode","+84","zoneId","Asia/Ho_Chi_Minh"),
                    Map.of("callingCode","+86","zoneId","Asia/Shanghai")),
            "effectiveFrom","2026-10-25T16:00:00Z","lastLegacyInstanceKey","WEEK:2026-W43"));
    final AuditReplayContext context = new AuditReplayContext("checker","注册国家码每周地区映射审批","idem-h3-1");

    @BeforeEach void setup() {
        when(config.activeValue(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(config.activeValueForUpdate(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(config.insertAdminValueIfMissing(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(call -> stored.compareAndSet(null,call.getArgument(1)));
        doAnswer(call -> { stored.set(call.getArgument(1)); return null; }).when(config)
                .upsertAdminValue(anyString(),anyString(),anyString(),anyString(),anyString());
        when(mapper.openLegacy(any())).thenReturn(List.of(new WeeklyRegionPolicyMapper.LegacyWindow("WEEK:2026-W43",
                LocalDateTime.of(2026,10,19,0,0),LocalDateTime.of(2026,10,26,0,0))));
    }
    @AfterEach void clear() { A2ReplayContext.exitReplay(); SecurityContextHolder.clearContext(); }
    void auth(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("maker","n/a",
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
    AuditOperationProposalRequest request(String target) {
        return new AuditOperationProposalRequest("forged action",WeeklyRegionPolicyService.TARGET,"forged before","forged after",
                "maker","growth","fund",false,false,"no approval","注册国家码每周地区映射审批","H",command,
                new AuditLockTarget("H","config",target),null);
    }

    @Test void exactBusinessPermissionAndTargetAreRequiredAndAuditCopyIsCanonical() {
        auth("platform_a2_proposal_create","growth_h3_read");
        assertThat(guard.validateProposal(command).getCode()).isEqualTo(403);
        assertThat(guard.validateApproval(command).getCode()).isEqualTo(403);
        auth("platform_a2_proposal_create","growth_h3_write");
        assertThat(guard.validateProposal(command).getCode()).isZero();
        assertThat(guard.validateApproval(command).getCode()).isZero();
        assertThat(guard.validateProposalContext(request("other-policy")).getCode()).isEqualTo(403);
        var descriptor = guard.validateProposalContext(request(WeeklyRegionPolicyService.TARGET)).getData();
        assertThat(descriptor.action()).isEqualTo("调整每周任务地区映射");
        assertThat(descriptor.amplifies()).isTrue();
        assertThat(descriptor.beforeValue()).contains("\"revision\":0").doesNotContain("forged");
        assertThat(descriptor.afterValue()).contains("2026-10-25T16:00:00Z","WEEK:2026-W43","\"revision\":1");
        verifyNoInteractions(snapshots,audit);
        assertThat(stored.get()).isNull();
    }

    @Test void realDispatcherReplayRejectsBypassThenFreezesBeforeAtomicPublicationAndAudits() {
        assertThat(dispatcher.dispatch(command,context).getCode()).isEqualTo(409);
        verifyNoInteractions(snapshots,audit);
        A2ReplayContext.enterReplay("WO-H3-REGION-1");
        assertThat(dispatcher.dispatch(command,context).getCode()).isZero();
        var order = inOrder(snapshots,config,audit);
        order.verify(snapshots).beforeRegionPolicyChange(Set.of("Asia/Ho_Chi_Minh","Asia/Shanghai"));
        order.verify(config).insertAdminValueIfMissing(eq(WeeklyRegionPolicyService.KEY),anyString(),eq("JSON"),eq("growth_quest"),anyString());
        order.verify(config).activeValueForUpdate(WeeklyRegionPolicyService.KEY);
        order.verify(config).upsertAdminValue(eq(WeeklyRegionPolicyService.KEY),anyString(),eq("JSON"),eq("growth_quest"),anyString());
        var captured = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        order.verify(audit).recordRequired(captured.capture());
        assertThat(new ObjectMapper().findAndRegisterModules().valueToTree(captured.getValue()).toString())
                .contains("before","after","reason","注册国家码每周地区映射审批");
        assertThat(policy.read().revision()).isEqualTo(1);
        assertThatThrownBy(() -> dispatcher.dispatch(command,new AuditReplayContext("checker",context.reason(),"different-key")))
                .hasMessage("WEEKLY_REGION_VERSION_CONFLICT");
        verify(config,times(1)).upsertAdminValue(any(),any(),any(),any(),any());
        verify(audit,times(1)).recordRequired(any());
    }

    @Test void boundaryChangesBetweenPreviewAndApprovalCannotPublish() {
        auth("platform_a2_proposal_create","growth_h3_write");
        assertThat(guard.validateProposal(command).getCode()).isZero();
        when(mapper.openLegacy(any())).thenReturn(List.of());
        assertThat(guard.validateProposal(command).getCode()).isEqualTo(409);
        A2ReplayContext.enterReplay("WO-H3-REGION-1");
        assertThatThrownBy(() -> dispatcher.dispatch(command,context)).hasMessage("WEEKLY_REGION_ACTIVATION_CONFLICT");
        verifyNoInteractions(snapshots,audit);
        assertThat(stored.get()).isNull();
    }

    @Test void lockedPublicationRevalidatesAfterConcurrentRevisionWins() {
        doAnswer(call -> {
            stored.set(policy.encode(policy.candidate(policy.proposal(command.params()),policy.read())));
            return null;
        }).when(snapshots).beforeRegionPolicyChange(anySet());
        A2ReplayContext.enterReplay("WO-H3-REGION-1");
        assertThatThrownBy(() -> dispatcher.dispatch(command,context)).hasMessage("WEEKLY_REGION_VERSION_CONFLICT");
        verify(config,never()).upsertAdminValue(any(),any(),any(),any(),any());
        verifyNoInteractions(audit);
    }

    @Test void normalH3GetExposesServerPreviewUnderReadPermissionWithoutMutation() throws Exception {
        var controller = new ffdd.opsconsole.growth.web.OpsGrowthController(growth,null,null);
        var response = controller.weeklyRegionPolicy();
        assertThat(response.getCode()).isZero();
        assertThat(response.getData().effectiveFrom()).isNull();
        assertThat(response.getData().lastLegacyInstanceKey()).isNull();
        assertThat(response.getData().nextActivation().effectiveFrom()).isEqualTo(Instant.parse("2026-10-25T16:00:00Z"));
        var endpoint = controller.getClass().getMethod("weeklyRegionPolicy");
        assertThat(endpoint.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
                .containsExactly("/quest-events/weekly-region-policy");
        assertThat(endpoint.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("hasAuthority('growth_h3_read')");
        verifyNoInteractions(snapshots,audit);
        assertThat(stored.get()).isNull();
    }
}
