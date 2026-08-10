package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.application.DisclosureContentHash;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AuditReplayBusinessPermissionGuardTest {
    private final TrustDisclosureRepository repository = mock(TrustDisclosureRepository.class);
    private final AdminOperatorRoleResolver roleResolver = mock(AdminOperatorRoleResolver.class);
    private final EmergencyControlRepository emergencyRepository = mock(EmergencyControlRepository.class);
    private final AuditReplayBusinessPermissionGuard guard =
            new AuditReplayBusinessPermissionGuard(repository, roleResolver, emergencyRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void standardSectionProposalRejectsUserWithoutStandardPublishPermission() {
        when(repository.listTrustSections()).thenReturn(List.of(
                new TrustSectionView("leadership", "团队", "卡片", "v1", "published", "today", "内容", false)));
        authenticate("content_i4_trust_section_manage");

        var result = guard.validateProposal(sectionCommand("leadership", "publish"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).endsWith("content_i4_publish_standard");
    }

    @Test
    void sensitiveSectionProposalRejectsUserWithOnlyStandardPublishPermission() {
        when(repository.listTrustSections()).thenReturn(List.of(
                new TrustSectionView("financials", "财务", "指标", "v1", "published", "today", "合规", true)));
        authenticate("content_i4_publish_standard");

        var result = guard.validateProposal(sectionCommand("financials", "rollback"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).endsWith("content_i4_trust_section_manage");
    }

    @Test
    void databaseHighSensitivityAlsoRequiresSensitivePermission() {
        when(repository.listTrustSections()).thenReturn(List.of(
                new TrustSectionView("customRisk", "高敏自定义版块", "指标", "v1", "published", "today", "合规", true)));
        authenticate("content_i4_publish_standard");

        assertThat(guard.validateProposal(sectionCommand("customRisk", "archive")).getCode()).isEqualTo(403);
    }

    @Test
    void matchingBusinessPermissionAllowsProposal() {
        when(repository.listTrustSections()).thenReturn(List.of());
        authenticate("content_i4_trust_section_manage", "content_i5_disclosure_publish", "content_i5_gate_adjust");
        DisclosureDraftView unhashed = new DisclosureDraftView(
                "v13", "SFC", "zh+vi+en", "2026-07-13", true,
                "中文", "Tiếng Việt", "English", "draft", 2L, "");
        String hash = DisclosureContentHash.from(unhashed, List.of());
        DisclosureDraftView draft = new DisclosureDraftView(
                unhashed.version(), unhashed.jurisdiction(), unhashed.languageScope(), unhashed.effectiveDate(),
                unhashed.requiresReack(), unhashed.zh(), unhashed.vi(), unhashed.en(), "draft", 2L, hash);
        when(repository.findDisclosureVersion("SFC", "v13")).thenReturn(Optional.of(draft));
        when(repository.listChapters("SFC", "v13")).thenReturn(List.of());

        assertThat(guard.validateProposal(sectionCommand("nexNarrative", "publish")).getCode()).isZero();
        assertThat(guard.validateProposal(new AuditReplayCommand("I", "i5_disclosure_publish", Map.of(
                "jurisdiction", "SFC", "version", "v13",
                "expectedRevision", 2L, "expectedContentHash", hash))).getCode()).isZero();
        assertThat(guard.validateProposal(new AuditReplayCommand("I", "i5_matrix_configure", Map.of())).getCode()).isZero();
        assertThat(guard.validateProposal(new AuditReplayCommand("I", "i5_gate_adjust", Map.of())).getCode()).isZero();
    }

    @Test
    void disclosureProposalRejectsChangedImmutableSnapshot() {
        authenticate("content_i5_disclosure_publish");
        DisclosureDraftView draft = new DisclosureDraftView(
                "v13", "SFC", "zh+vi+en", "2026-07-13", true,
                "中文", "Tiếng Việt", "English", "draft", 3L, "server-hash");
        when(repository.findDisclosureVersion("SFC", "v13")).thenReturn(Optional.of(draft));
        when(repository.listChapters("SFC", "v13")).thenReturn(List.of());

        var result = guard.validateProposal(new AuditReplayCommand("I", "i5_disclosure_publish", Map.of(
                "jurisdiction", "SFC", "version", "v13",
                "expectedRevision", 2L, "expectedContentHash", "client-hash")));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("A2_DISCLOSURE_SNAPSHOT_CHANGED");
    }

    @Test
    void jurisdictionLifecycleProposalRequiresDisclosurePublishPermission() {
        authenticate("content_i5_write");

        for (String operation : List.of("i5_jurisdiction_status", "i5_jurisdiction_delete")) {
            var result = guard.validateProposal(new AuditReplayCommand("I", operation, Map.of()));
            assertThat(result.getCode()).isEqualTo(403);
            assertThat(result.getMessage()).endsWith("content_i5_disclosure_publish");
        }
    }

    @Test
    void i3CapAdjustmentUsesItsExactBusinessPermissionForProposalAndReplayActors() {
        AuditReplayCommand command = new AuditReplayCommand("I", "i3_cap_adjust", Map.of(
                "tier", "critical", "cap", "50", "expectedCap", "∞ 永不淘汰"));

        // A delegated proposer may create the A2 ticket only with the I3 CAP authority.
        authenticate("platform_a2_proposal_create", "content_i3_cap_adjust");
        assertThat(guard.validateProposal(command).getCode()).isZero();

        // Missing business authority remains fail-closed for a delegated proposer.
        authenticate("platform_a2_proposal_create");
        var delegatedDenied = guard.validateProposal(command);
        assertThat(delegatedDenied.getCode()).isEqualTo(403);
        assertThat(delegatedDenied.getMessage()).endsWith("content_i3_cap_adjust");

        // The real approval entry authority still needs the I3 business authority at replay time.
        authenticate("platform_a2_operation_approve");
        var replayDenied = guard.validateProposal(command);
        assertThat(replayDenied.getCode()).isEqualTo(403);
        assertThat(replayDenied.getMessage()).endsWith("content_i3_cap_adjust");

        authenticate("platform_a2_operation_approve", "content_i3_cap_adjust");
        assertThat(guard.validateProposal(command).getCode()).isZero();
    }

    @Test
    void i3CapProposalCanonicalizesItsVisibleTierAndExactCasLock() {
        AuditReplayCommand command = new AuditReplayCommand("I", "i3_cap_adjust", Map.of(
                "tier", "critical", "cap", "50", "expectedCap", "∞ 永不淘汰"));
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "client supplied action", "critical", "client before", "50",
                "i3-maker", "CONTENT", "param", false, false,
                "client gate", "adjust critical cap", "I3", command,
                new AuditLockTarget("I", "notification_cap", "critical"), null);

        var result = guard.validateProposalContext(request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::beforeValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::afterValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::operationType,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies)
                .containsExactly(
                        "调整通知优先级 CAP · critical",
                        "critical",
                        "以服务器执行时状态为准",
                        "50 条",
                        "I3",
                        "param",
                        false);
        assertThat(result.getData().target())
                .isEqualTo(new AuditLockTarget("I", "notification_cap", "critical"));
    }

    @Test
    void i3CapProposalRejectsMissingCasOrMismatchedNotificationLock() {
        authenticate("platform_a2_proposal_create", "content_i3_cap_adjust");
        AuditReplayCommand missingExpectedCap = new AuditReplayCommand("I", "i3_cap_adjust", Map.of(
                "tier", "critical", "cap", "50"));
        AuditOperationProposalRequest invalid = new AuditOperationProposalRequest(
                "x", "critical", "x", "50", "i3-maker", "CONTENT", "param", false, false,
                "x", "x", "I3", missingExpectedCap,
                new AuditLockTarget("I", "notification_cap", "critical"), null);
        assertThat(guard.validateProposalContext(invalid).getCode()).isEqualTo(403);

        AuditReplayCommand command = new AuditReplayCommand("I", "i3_cap_adjust", Map.of(
                "tier", "critical", "cap", "50", "expectedCap", "40"));
        AuditOperationProposalRequest wrongLock = new AuditOperationProposalRequest(
                "x", "critical", "x", "50", "i3-maker", "CONTENT", "param", false, false,
                "x", "x", "I3", command,
                new AuditLockTarget("I", "notification_cap", "high"), null);
        assertThat(guard.validateProposalContext(wrongLock).getCode()).isEqualTo(403);
    }

    @Test
    void i3CapProposalCanonicalizesCaseWhitespaceAndEquivalentCapSpellingsIntoOneLock() {
        AuditReplayCommand command = new AuditReplayCommand("I", "i3_cap_adjust", Map.of(
                "tier", "  Critical ", "cap", "005 条", "expectedCap", "50 条"));
        AuditOperationProposalRequest canonicalTarget = new AuditOperationProposalRequest(
                "x", "critical", "x", "5 条", "i3-maker", "CONTENT", "param", false, false,
                "x", "x", "I3", command,
                new AuditLockTarget("I", "notification_cap", "critical"), null);

        var result = guard.validateProposalContext(canonicalTarget);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().objectId()).isEqualTo("critical");
        assertThat(result.getData().afterValue()).isEqualTo("5 条");
        assertThat(result.getData().target()).isEqualTo(new AuditLockTarget("I", "notification_cap", "critical"));

        AuditOperationProposalRequest mixedCaseLock = new AuditOperationProposalRequest(
                "x", "Critical", "x", "005条", "i3-maker", "CONTENT", "param", false, false,
                "x", "x", "I3", command,
                new AuditLockTarget("I", "notification_cap", "Critical"), null);
        assertThat(guard.validateProposalContext(mixedCaseLock).getCode()).isEqualTo(403);
    }

    @Test
    void i3CapProposalRejectsUnknownTierAndMalformedOrOutOfRangeCap() {
        authenticate("platform_a2_proposal_create", "content_i3_cap_adjust");
        for (Map<String, Object> params : List.<Map<String, Object>>of(
                Map.<String, Object>of("tier", "urgent", "cap", "5", "expectedCap", "50 条"),
                Map.<String, Object>of("tier", "critical", "cap", "0", "expectedCap", "50 条"),
                Map.<String, Object>of("tier", "critical", "cap", "10001", "expectedCap", "50 条"),
                Map.<String, Object>of("tier", "critical", "cap", "5/day", "expectedCap", "50 条"))) {
            AuditReplayCommand command = new AuditReplayCommand("I", "i3_cap_adjust", params);
            AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                    "x", "critical", "x", "x", "i3-maker", "CONTENT", "param", false, false,
                    "x", "x", "I3", command,
                    new AuditLockTarget("I", "notification_cap", "critical"), null);
            assertThat(guard.validateProposalContext(request).getCode()).isEqualTo(403);
        }
    }

    @Test
    void j4MakerMustHoldEveryFrozenTargetAuthorityForAMixedPlaybook() {
        AuditReplayCommand raw = new AuditReplayCommand("J", "j4_playbook_execute", Map.of(
                "code", "SOP-MIXED-1", "emergency", true));
        stubPlaybook("SOP-MIXED-1", "2026-08-01T12:00:00", List.of("J1", "J2", "I3"));
        AuditReplayCommand command = guard.canonicalizeProposalCommand(raw).getData();

        authenticate("platform_a2_proposal_create", "emergency_j4_playbook_execute",
                "emergency_j1_gate_kill", "emergency_j2_emergency_block");
        var denied = guard.validateProposal(command);

        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("content_i3_write");

        authenticate("platform_a2_proposal_create", "emergency_j4_playbook_execute",
                "emergency_j1_gate_kill", "emergency_j2_emergency_block", "content_i3_write");
        assertThat(guard.validateProposal(command).getCode()).isZero();
    }

    @Test
    void j4DedicatedCheckerApprovesFrozenMixedPlaybookWithoutBecomingEveryTargetWriter() {
        AuditReplayCommand raw = new AuditReplayCommand("J", "j4_playbook_execute", Map.of(
                "code", "SOP-MIXED-2", "emergency", true));
        stubPlaybook("SOP-MIXED-2", "2026-08-01T12:01:00", List.of("J1", "J2", "I3"));
        authenticate("platform_a2_proposal_create", "emergency_j4_playbook_execute",
                "emergency_j1_gate_kill", "emergency_j2_emergency_block", "content_i3_write");
        AuditReplayCommand command = guard.canonicalizeProposalCommand(raw).getData();
        assertThat(guard.validateProposal(command).getCode()).isZero();

        authenticate("platform_a2_operation_approve", "emergency_j4_playbook_execute");
        assertThat(guard.validateApproval(command).getCode()).isZero();

        authenticate("platform_a2_operation_approve");
        var denied = guard.validateApproval(command);
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("emergency_j4_playbook_execute");
    }

    @Test
    void j4ApprovalFailsClosedWhenThePlaybookDefinitionChangesAfterProposal() {
        AuditReplayCommand raw = new AuditReplayCommand("J", "j4_playbook_execute", Map.of(
                "code", "SOP-MIXED-3", "emergency", true));
        stubPlaybook("SOP-MIXED-3", "2026-08-01T12:02:00", List.of("J1", "J2"));
        authenticate("platform_a2_proposal_create", "emergency_j4_playbook_execute",
                "emergency_j1_gate_kill", "emergency_j2_emergency_block");
        AuditReplayCommand command = guard.canonicalizeProposalCommand(raw).getData();

        stubPlaybook("SOP-MIXED-3", "2026-08-01T12:03:00", List.of("J1", "J2", "I3"));
        authenticate("platform_a2_operation_approve", "emergency_j4_playbook_execute");
        var changed = guard.validateApproval(command);

        assertThat(changed.getCode()).isEqualTo(409);
        assertThat(changed.getMessage()).isEqualTo("J4_PLAYBOOK_SNAPSHOT_CHANGED");
    }

    private AuditReplayCommand sectionCommand(String sectionKey, String action) {
        return new AuditReplayCommand("I", "i4_trust_section_manage", Map.of(
                "sectionKey", sectionKey, "action", action));
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "proposer", "n/a", java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private void stubPlaybook(String code, String version, List<String> domains) {
        List<Map<String, Object>> sequence = domains.stream()
                .map(domain -> Map.<String, Object>of(
                        "domain", domain,
                        "action", domain + " action",
                        "ref", domain.toLowerCase() + "-ref",
                        "approve", true))
                .toList();
        when(emergencyRepository.playbookForUpdate(code)).thenReturn(Optional.of(Map.of(
                "code", code,
                "version", version,
                "sequence", sequence)));
    }

    @Test
    void a6ReplayRequiresTheExactRoleMutationAuthority() {
        authenticate("platform_a6_read");

        var metadataDenied = guard.validateProposal(new AuditReplayCommand(
                "A", "a6_role_status_update", Map.of("roleId", 9L, "status", 0)));
        var grantsDenied = guard.validateProposal(new AuditReplayCommand(
                "A", "a6_role_grants_update", Map.of("roleId", 9L)));

        assertThat(metadataDenied.getCode()).isEqualTo(403);
        assertThat(metadataDenied.getMessage()).endsWith("platform_a6_write");
        assertThat(grantsDenied.getCode()).isEqualTo(403);
        assertThat(grantsDenied.getMessage()).endsWith("platform_a6_role_grants_update");
    }

    @Test
    void j1ImmediateOperationsCannotEnterTheLegacyA2ProposalPath() {
        authenticate("emergency_j1_gate_kill", "emergency_j1_gate_resume", "emergency_j1_batch_kill");

        for (String operation : List.of("j1_gate_kill", "j1_gate_resume", "j1_batch_kill")) {
            var result = guard.validateProposal(new AuditReplayCommand("J", operation, Map.of()));
            assertThat(result.getCode()).isEqualTo(409);
            assertThat(result.getMessage()).isEqualTo("J1_DIRECT_EXECUTION_REQUIRED");
        }
    }

    @Test
    void delegatedRiskProposalRequiresTheMatchingC2OrK1BusinessPermission() {
        authenticate("platform_a2_proposal_create", "risk_k1_cluster_freeze", "user_c2_account_freeze");

        assertThat(guard.validateProposal(new AuditReplayCommand(
                "K", "k1_cluster_freeze", Map.of("clusterId", "CL-1"))).getCode()).isZero();
        assertThat(guard.validateProposal(new AuditReplayCommand(
                "C", "c2_account_freeze", Map.of("userId", "1"))).getCode()).isZero();

        var denied = guard.validateProposal(new AuditReplayCommand(
                "K", "k1_cluster_release", Map.of("clusterId", "CL-1")));
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("risk_k1_cluster_release");
    }

    @Test
    void c3CreateReplayRequiresExactCreateAuthorityForProposalWriterAndApprover() {
        AuditReplayCommand command = new AuditReplayCommand(
                "C", "c3_adjust_create", Map.of("userId", 1L, "amount", "5", "asset", "USDT"));

        authenticate("platform_a2_proposal_create");
        assertBusinessAuthorityDenied(guard.validateProposal(command), "user_c3_adjust_create");
        authenticate("platform_a2_proposal_create", "user_c3_adjust_create");
        assertThat(guard.validateProposal(command).getCode()).isZero();

        authenticate("platform_a2_write");
        assertBusinessAuthorityDenied(guard.validateProposal(command), "user_c3_adjust_create");
        authenticate("platform_a2_write", "user_c3_adjust_create");
        assertThat(guard.validateProposal(command).getCode()).isZero();

        authenticate("platform_a2_operation_approve");
        assertBusinessAuthorityDenied(guard.validateApproval(command), "user_c3_adjust_create");
        authenticate("platform_a2_operation_approve", "user_c3_adjust_create");
        assertThat(guard.validateApproval(command).getCode()).isZero();
    }

    @Test
    void c3ReviewReplayRequiresExactApproveAuthorityForProposalAndApprover() {
        for (String operation : List.of("c3_adjust_approve", "c3_adjust_reject")) {
            AuditReplayCommand command = new AuditReplayCommand(
                    "C", operation, Map.of("adjustmentNo", "ADJ-C3-001"));

            authenticate("platform_a2_proposal_create", "user_c3_adjust_create");
            assertBusinessAuthorityDenied(guard.validateProposal(command), "user_c3_adjust_approve");
            authenticate("platform_a2_proposal_create", "user_c3_adjust_approve");
            assertThat(guard.validateProposal(command).getCode()).isZero();

            authenticate("platform_a2_operation_approve", "user_c3_adjust_create");
            assertBusinessAuthorityDenied(guard.validateApproval(command), "user_c3_adjust_approve");
            authenticate("platform_a2_operation_approve", "user_c3_adjust_approve");
            assertThat(guard.validateApproval(command).getCode()).isZero();
        }
    }

    private void assertBusinessAuthorityDenied(ApiResult<Void> result, String authority) {
        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("A2_BUSINESS_PERMISSION_DENIED:" + authority);
    }

    @Test
    void delegatedK2FreezeProposalRequiresItsExactBusinessPermission() {
        authenticate("platform_a2_proposal_create", "risk_k2_row_freeze");

        assertThat(guard.validateProposal(new AuditReplayCommand(
                "K", "k2_row_freeze", Map.of(
                        "rowId", "T-318", "expectedVersion", 0, "clusterExpectedVersion", 0))).getCode()).isZero();

        var denied = guard.validateProposal(new AuditReplayCommand(
                "K", "k2_row_flag", Map.of("rowId", "T-318", "expectedVersion", 0)));
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("risk_k2_row_flag");
    }

    @Test
    void e4ReplayRequiresWriteOrRefundBusinessAuthority() {
        authenticate("device_e4_read");

        var refundDenied = guard.validateProposal(new AuditReplayCommand(
                "E", "e4_order_refund", Map.of("orderNo", "OD-1")));
        var stateDenied = guard.validateProposal(new AuditReplayCommand(
                "E", "e4_order_state", Map.of("orderNo", "OD-1", "state", "paid")));

        assertThat(refundDenied.getCode()).isEqualTo(403);
        assertThat(refundDenied.getMessage()).endsWith("device_e4_order_refund");
        assertThat(stateDenied.getCode()).isEqualTo(403);
        assertThat(stateDenied.getMessage()).endsWith("device_e4_write");
    }

    @Test
    void delegatedE3ConfigRequiresExactWriteAuthorityAndUsesCanonicalContext() {
        String frontendKey = "E.device.capacity.subsidyDays";
        AuditReplayCommand command = new AuditReplayCommand(
                "E", "e3_config", Map.of("key", frontendKey, "value", "31"));

        authenticate("platform_a2_proposal_create");
        var denied = guard.validateProposal(command);
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("device_e3_write");

        authenticate("platform_a2_proposal_create", "device_e3_write");
        assertThat(guard.validateProposal(command).getCode()).isZero();
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "client supplied action",
                frontendKey,
                "30",
                "31",
                "e3-maker",
                "custom",
                "param",
                false,
                false,
                "client gate",
                "adjust display-only subsidy window",
                "E3",
                command,
                new AuditLockTarget("E", "device_e3_config", "capacitySubsidyDays"),
                null);

        var canonical = guard.validateProposalContext(request);

        assertThat(canonical.getCode()).isZero();
        assertThat(canonical.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::beforeValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::afterValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::operationType,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies)
                .containsExactly(
                        "更新 E3 生命周期配置 · " + frontendKey,
                        frontendKey,
                        "以服务器执行时状态为准",
                        "31",
                        "E3",
                        "param",
                        false);
        assertThat(canonical.getData().target())
                .isEqualTo(new AuditLockTarget("E", "device_e3_config", "capacitySubsidyDays"));

        AuditOperationProposalRequest rawTarget = new AuditOperationProposalRequest(
                request.action(), request.obj(), request.beforeValue(), request.afterValue(),
                request.operator(), request.operatorRole(), request.type(), request.amplifies(), request.sos(),
                request.roleGate(), request.reason(), request.sourceDomain(), command,
                new AuditLockTarget("E", "device_e3_config", frontendKey), null);
        assertThat(guard.validateProposalContext(rawTarget).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_MISMATCH");
    }

    @Test
    void e3AndE6CheckersApproveWithA2AndExactReadScopeButRemainDirectWriteDenied() {
        AuditReplayCommand e3 = new AuditReplayCommand(
                "E", "e3_config", Map.of("key", "E.device.capacity.subsidyDays", "value", "31"));
        AuditReplayCommand e6 = new AuditReplayCommand(
                "E", "e6_compute_config", Map.of("paramKey", "E.compute.computeShareEnabled", "value", "on"));

        authenticate("platform_a2_operation_approve", "device_e3_read", "device_e6_read");
        assertThat(guard.validateApproval(e3).getCode()).isZero();
        assertThat(guard.validateApproval(e6).getCode()).isZero();
        assertThat(guard.validateProposal(e3).getCode()).isEqualTo(403);
        assertThat(guard.validateProposal(e6).getCode()).isEqualTo(403);

        authenticate("platform_a2_operation_approve", "device_e3_read");
        var e6Denied = guard.validateApproval(e6);
        assertThat(e6Denied.getCode()).isEqualTo(403);
        assertThat(e6Denied.getMessage()).endsWith("device_e6_read");
    }

    @Test
    void delegatedE3ConfigFailsClosedForUnknownKeyAndDoesNotAuthorizeOtherEOps() {
        authenticate("platform_a2_proposal_create", "device_e3_write");

        AuditReplayCommand unknownKey = new AuditReplayCommand(
                "E", "e3_config", Map.of("key", "E.device.capacity.unknown", "value", "31"));
        AuditOperationProposalRequest unknownContext = new AuditOperationProposalRequest(
                "client supplied action",
                "E.device.capacity.unknown",
                "30",
                "31",
                "e3-maker",
                "custom",
                "param",
                false,
                false,
                "client gate",
                "unknown E3 keys must fail closed",
                "E3",
                unknownKey,
                new AuditLockTarget("E", "device_e3_config", "capacityUnknown"),
                null);

        assertThat(guard.validateProposalContext(unknownContext).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");

        var unknownOperation = guard.validateProposal(new AuditReplayCommand(
                "E", "e3_unknown_operation", Map.of()));
        assertThat(unknownOperation.getCode()).isEqualTo(403);
        assertThat(unknownOperation.getMessage()).isEqualTo("A2_BUSINESS_PERMISSION_UNMAPPED");

        var e5Operation = guard.validateProposal(new AuditReplayCommand(
                "E", "e5_device_force_activate", Map.of("deviceId", "42")));
        assertThat(e5Operation.getCode()).isEqualTo(403);
        assertThat(e5Operation.getMessage()).endsWith("device_e5_device_force_activate");
    }

    @Test
    void delegatedE3FinancialConfigIsConservativelyMarkedAsAmplifying() {
        authenticate("platform_a2_proposal_create", "device_e3_write");
        AuditReplayCommand command = new AuditReplayCommand(
                "E", "e3_config", Map.of("key", "E.tradein.enabled", "value", "true"));
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "client supplied action",
                "E.tradein.enabled",
                "false",
                "true",
                "e3-maker",
                "custom",
                "param",
                false,
                false,
                "client gate",
                "enable trade-in",
                "E3",
                command,
                new AuditLockTarget("E", "device_e3_config", "tradeinEnabled"),
                null);

        var canonical = guard.validateProposalContext(request);

        assertThat(canonical.getCode()).isZero();
        assertThat(canonical.getData().amplifies()).isTrue();
        assertThat(canonical.getData().target())
                .isEqualTo(new AuditLockTarget("E", "device_e3_config", "tradeinEnabled"));
    }

    @Test
    void delegatedFConfigProposalRequiresExactDomainPermissionAndCanonicalContext() {
        AuditReplayCommand prize = new AuditReplayCommand(
                "F", "f_ui_config", Map.of("key", "F.prize.name", "value", "Nexion V-Rank"));

        authenticate("platform_a2_proposal_create", "finance_d2_withdrawal_approve");
        var crossDomainDenied = guard.validateProposal(prize);
        assertThat(crossDomainDenied.getCode()).isEqualTo(403);
        assertThat(crossDomainDenied.getMessage()).endsWith("network_f1_write");

        authenticate("platform_a2_proposal_create", "network_f1_write");
        assertThat(guard.validateProposal(prize).getCode()).isZero();
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "client copy", "F.prize.name", "old", "Nexion V-Rank", "growth", "growth",
                "param", false, false, "client gate", "update F1 display copy", "F1", prize,
                new AuditLockTarget("F", "ui_config", "F.prize.name"), null);

        var canonical = guard.validateProposalContext(request);

        assertThat(canonical.getCode()).isZero();
        assertThat(canonical.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::operationType,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies)
                .containsExactly(
                        "F 域配置调整 · F.prize.name",
                        "F.prize.name",
                        "F1",
                        "param",
                        false);
    }

    @Test
    void delegatedF5CommissionDispositionBindsExactEventVersionAndRiskDirection() {
        AuditReplayCommand freeze = new AuditReplayCommand(
                "F", "f_commission_status", Map.of(
                        "key", "F.commission.CM-71.status",
                        "value", "frozen",
                        "expectedVersion", 6));
        authenticate("platform_a2_proposal_create", "network_f5_commission_dispose");

        assertThat(guard.validateProposal(freeze).getCode()).isZero();
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "client copy", "F.commission.CM-71.status", "cooling", "frozen",
                "maker", "operator", "fund", true, false, "client gate",
                "freeze isolated commission", "F5", freeze,
                new AuditLockTarget("F", "commission_event", "CM-71"), null);

        var canonical = guard.validateProposalContext(request);

        assertThat(canonical.getCode()).isZero();
        assertThat(canonical.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::operationType,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::target)
                .containsExactly(
                        "F.commission.CM-71.status", "F5", "param", false,
                        new AuditLockTarget("F", "commission_event", "CM-71"));

        AuditReplayCommand unlock = new AuditReplayCommand(
                "F", "f_commission_status", Map.of(
                        "key", "F.commission.CM-71.status",
                        "value", "unlocked",
                        "expectedVersion", 6));
        var unlockCanonical = guard.validateProposalContext(new AuditOperationProposalRequest(
                "client copy", "F.commission.CM-71.status", "cooling", "unlocked",
                "maker", "operator", "fund", true, false, "client gate",
                "unlock isolated commission", "F5", unlock,
                new AuditLockTarget("F", "commission_event", "CM-71"), null));
        assertThat(unlockCanonical.getCode()).isZero();
        assertThat(unlockCanonical.getData().amplifies()).isTrue();
        assertThat(unlockCanonical.getData().operationType()).isEqualTo("fund");

        AuditReplayCommand nonCanonicalNumericKey = new AuditReplayCommand(
                "F", "f_commission_status", Map.of(
                        "key", "F.commission.71.status", "value", "frozen", "expectedVersion", 6));
        assertThat(guard.validateProposalContext(new AuditOperationProposalRequest(
                "copy", "F.commission.71.status", "cooling", "frozen",
                "maker", "operator", "param", false, false, "gate", "reason", "F5",
                nonCanonicalNumericKey, new AuditLockTarget("F", "commission_event", "71"), null))
                .getMessage()).isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");

        AuditReplayCommand missingVersion = new AuditReplayCommand(
                "F", "f_commission_status", Map.of(
                        "key", "F.commission.CM-71.status", "value", "frozen"));
        assertThat(guard.validateProposalContext(new AuditOperationProposalRequest(
                "copy", "F.commission.CM-71.status", "cooling", "frozen",
                "maker", "operator", "param", false, false, "gate", "reason", "F5",
                missingVersion, new AuditLockTarget("F", "commission_event", "CM-71"), null))
                .getMessage()).isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");
    }

    @Test
    void f5DirectDispositionProposalsRequireExactOperationPermissionAndLocks() {
        record Scenario(
                AuditReplayCommand command,
                String permission,
                String objectId,
                AuditLockTarget target,
                List<AuditLockTarget> targets) {
        }
        List<Scenario> scenarios = List.of(
                new Scenario(
                        new AuditReplayCommand("F", "f5_commission_reverse", Map.of(
                                "commissionId", "CM-41", "refundRef", "refund-41")),
                        "network_f5_commission_reject", "CM-41",
                        new AuditLockTarget("F", "commission_event", "CM-41"), null),
                new Scenario(
                        new AuditReplayCommand("F", "f5_commission_reissue", Map.of(
                                "commissionIds", List.of("CM-41", "CM-42"))),
                        "network_f5_commission_dispose", "CM-41,CM-42", null,
                        List.of(
                                new AuditLockTarget("F", "commission_event", "CM-41"),
                                new AuditLockTarget("F", "commission_event", "CM-42"))),
                new Scenario(
                        new AuditReplayCommand("F", "f5_commission_suspension", Map.of(
                                "userId", 41, "kinds", List.of("binary", "network"), "suspended", true)),
                        "network_f5_commission_reject", "41:binary,network",
                        new AuditLockTarget("F", "commission_user_kind", "41:binary,network"), null));

        for (Scenario scenario : scenarios) {
            authenticate("platform_a2_proposal_create");
            assertThat(guard.validateProposal(scenario.command()).getMessage()).endsWith(scenario.permission());

            authenticate("platform_a2_proposal_create", scenario.permission());
            AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                    "client copy", scenario.objectId(), "before", "after", "maker", "operator",
                    "fund", true, false, "client gate", "review F5 disposition", "F5",
                    scenario.command(), scenario.target(), scenario.targets());
            assertThat(guard.validateProposalContext(request).getCode()).isZero();

            AuditOperationProposalRequest missingOrWrongLock = new AuditOperationProposalRequest(
                    request.action(), request.obj(), request.beforeValue(), request.afterValue(),
                    request.operator(), request.operatorRole(), request.type(), request.amplifies(), request.sos(),
                    request.roleGate(), request.reason(), request.sourceDomain(), scenario.command(),
                    scenario.target() == null ? null : new AuditLockTarget("F", "commission_event", "CM-99"),
                    scenario.targets() == null ? null : List.of());
            assertThat(guard.validateProposalContext(missingOrWrongLock).getCode()).isEqualTo(403);
        }

        authenticate("platform_a2_proposal_create", "network_f5_commission_reject");
        AuditReplayCommand crossCall = new AuditReplayCommand("F", "f5_commission_reverse", Map.of(
                "commissionIds", List.of("CM-41")));
        assertThat(guard.validateProposal(crossCall).getCode()).isZero();
        assertThat(guard.validateProposalContext(new AuditOperationProposalRequest(
                "reverse", "CM-41", "before", "after", "maker", "operator", "fund", false,
                false, "gate", "attempt cross-call", "F5", crossCall,
                new AuditLockTarget("F", "commission_event", "CM-41"), null)).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");
    }

    @Test
    void allFConfigReplayFamiliesMapPermissionSourceDomainAndLockFailClosed() {
        record Scenario(
                AuditReplayCommand command,
                String authority,
                String sourceDomain,
                String targetType,
                String targetId) {
        }
        List<Scenario> scenarios = List.of(
                new Scenario(
                        new AuditReplayCommand("F", "f_config", Map.of(
                                "key", "directRoyaltyPct", "value", "10")),
                        "network_f2_royalty_rate", "F2", "team_config", "directRoyaltyPct"),
                new Scenario(
                        new AuditReplayCommand("F", "f_config", Map.of(
                                "key", "binaryPairRatePct", "value", "10")),
                        "network_f2_royalty_rate", "F3", "team_config", "binaryPairRatePct"),
                new Scenario(
                        new AuditReplayCommand("F", "f_unilevel_rule", Map.of(
                                "key", "F.unilevel.nex.L3", "value", "0.4")),
                        "network_f2_royalty_rate", "F2", "unilevel_rule", "L3"),
                new Scenario(
                        new AuditReplayCommand("F", "f_ui_config", Map.of(
                                "key", "F.binary.matchRate", "value", "13")),
                        "network_f3_match_rate", "F3", "ui_config", "F.binary.matchRate"),
                new Scenario(
                        new AuditReplayCommand("F", "f_ui_config", Map.of(
                                "key", "F.binary.paused", "value", "on")),
                        "network_f3_engine_pause", "F3", "ui_config", "F.binary.paused"),
                new Scenario(
                        new AuditReplayCommand("F", "f_ui_config", Map.of(
                                "key", "F.pool.ratio", "value", "30")),
                        "network_f4_pool_fund", "F4", "ui_config", "F.pool.ratio"),
                new Scenario(
                        new AuditReplayCommand("F", "f_ui_config", Map.of(
                                "key", "F.pool.monthlyCap", "value", "10000")),
                        "network_f4_write", "F4", "ui_config", "F.pool.monthlyCap"),
                new Scenario(
                        new AuditReplayCommand("F", "f_ui_config", Map.of(
                                "key", "F.unilevel.L3.paused", "value", "on")),
                        "network_f2_policy_amplify", "F2", "ui_config", "F.unilevel.L3.paused"));

        for (Scenario scenario : scenarios) {
            authenticate("platform_a2_proposal_create");
            var denied = guard.validateProposal(scenario.command());
            assertThat(denied.getCode()).isEqualTo(403);
            assertThat(denied.getMessage()).endsWith(scenario.authority());

            authenticate("platform_a2_proposal_create", scenario.authority());
            assertThat(guard.validateProposal(scenario.command()).getCode()).isZero();
            AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                    "client copy",
                    String.valueOf(scenario.command().params().get("key")),
                    "old",
                    String.valueOf(scenario.command().params().get("value")),
                    "maker",
                    "growth",
                    "param",
                    false,
                    false,
                    "gate",
                    "validate F command",
                    scenario.sourceDomain(),
                    scenario.command(),
                    new AuditLockTarget("F", scenario.targetType(), scenario.targetId()),
                    null);
            assertThat(guard.validateProposalContext(request).getCode()).isZero();
        }

        authenticate("platform_a2_proposal_create", "network_f1_write");
        var unknown = guard.validateProposal(new AuditReplayCommand(
                "F", "f_ui_config", Map.of("key", "F.unknown.key", "value", "1")));
        assertThat(unknown.getCode()).isEqualTo(403);
        assertThat(unknown.getMessage()).isEqualTo("A2_BUSINESS_PERMISSION_UNMAPPED");
    }

    @Test
    void c5PasswordResetRequiresItsExactBusinessPermission() {
        authenticate("platform_a2_write", "user_c1hub_password_reset");

        var denied = guard.validateProposal(new AuditReplayCommand(
                "C", "c5_password_reset", Map.of("userId", "52")));

        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("user_c5_password_reset");

        authenticate("platform_a2_write", "user_c5_password_reset");
        assertThat(guard.validateProposal(new AuditReplayCommand(
                "C", "c5_password_reset", Map.of("userId", "52"))).getCode()).isZero();
    }

    @Test
    void everyRegisteredC5CommandRequiresItsCanonicalEndpointPermission() {
        Map<String, String> commands = Map.of(
                "c5_2fa_disable", "user_c5_2fa_disable",
                "c5_password_reset", "user_c5_password_reset",
                "c5_user_unlock", "user_c5_unlock_short",
                "c5_session_revoke_one", "user_c5_session_revoke_one");

        for (Map.Entry<String, String> entry : commands.entrySet()) {
            authenticate("platform_a2_write");
            var denied = guard.validateProposal(new AuditReplayCommand(
                    "C", entry.getKey(), Map.of("userId", "52")));
            assertThat(denied.getCode()).isEqualTo(403);
            assertThat(denied.getMessage()).endsWith(entry.getValue());

            authenticate("platform_a2_write", entry.getValue());
            assertThat(guard.validateProposal(new AuditReplayCommand(
                    "C", entry.getKey(), Map.of("userId", "52"))).getCode()).isZero();
        }
    }

    @Test
    void c5LongUnlockCannotFallBackToTheShortUnlockPermission() {
        authenticate("platform_a2_write", "user_c5_unlock_short");

        var denied = guard.validateProposal(new AuditReplayCommand(
                "C", "c5_user_unlock", Map.of("userId", "52", "lockKind", "LONG")));

        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("user_c5_unlock_long");

        authenticate("platform_a2_write", "user_c5_unlock_long");
        assertThat(guard.validateProposal(new AuditReplayCommand(
                "C", "c5_user_unlock", Map.of("userId", "52", "lockKind", "LONG"))).getCode()).isZero();
    }

    @Test
    void delegatedRiskProposalFailsClosedForUnmappedCommands() {
        authenticate("platform_a2_proposal_create", "risk_k1_cluster_freeze");

        var result = guard.validateProposal(new AuditReplayCommand("E", "e5_unknown_command", Map.of()));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("A2_BUSINESS_PERMISSION_UNMAPPED");
    }

    @Test
    void delegatedProposalRequiresACommand() {
        authenticate("platform_a2_proposal_create", "user_c2_account_freeze");

        var result = guard.validateProposal(null);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("A2_BUSINESS_COMMAND_REQUIRED");
    }

    @Test
    void delegatedProposalBindsDisplayedObjectAndLockToExecutableCommand() {
        authenticate("platform_a2_proposal_create", "user_c2_account_unfreeze");
        AuditReplayCommand command = new AuditReplayCommand(
                "C", "c2_account_unfreeze", Map.of("userId", 52, "status", "ACTIVE"));
        AuditOperationProposalRequest valid = new AuditOperationProposalRequest(
                "client supplied copy", "52", "client before", "client after", "risk-user", "risk",
                "param", false, true, "client gate", "restore verified account", "C2", command,
                new AuditLockTarget("C", "user", "52"), null);

        var allowed = guard.validateProposalContext(valid);
        AuditOperationProposalRequest mismatched = new AuditOperationProposalRequest(
                valid.action(), "99", valid.beforeValue(), valid.afterValue(), valid.operator(), valid.operatorRole(),
                valid.type(), valid.amplifies(), valid.sos(), valid.roleGate(), valid.reason(), valid.sourceDomain(),
                command, new AuditLockTarget("C", "user", "99"), null);
        var denied = guard.validateProposalContext(mismatched);

        assertThat(allowed.getCode()).isZero();
        assertThat(allowed.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::afterValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies)
                .containsExactly("恢复账户 · 52", "52", "ACTIVE", "C2", true);
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).isEqualTo("A2_BUSINESS_CONTEXT_MISMATCH");
    }

    @Test
    void delegatedGrowthProposalUsesCanonicalE5ForceAndUnbindContext() {
        when(roleResolver.resolveCode()).thenReturn("GROWTH");
        authenticate("platform_a2_proposal_create");

        AuditReplayCommand forceCommand = new AuditReplayCommand(
                "E", "e5_device_force_activate", Map.of("deviceId", "42"));
        AuditOperationProposalRequest forceRequest = new AuditOperationProposalRequest(
                "client force copy", "42", "client before", "client after", "growth", "growth",
                "sos", true, true, "client gate", "force device after manual review", "E5", forceCommand,
                new AuditLockTarget("E", "device", "42"), null);

        var forcePermission = guard.validateProposal(forceCommand);
        var force = guard.validateProposalContext(forceRequest);

        assertThat(forcePermission.getCode()).isZero();
        assertThat(force.getCode()).isZero();
        assertThat(force.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::afterValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::operationType,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies)
                .containsExactly("强制激活设备 · 42", "42", "ACTIVATED", "E5", "sos", false);

        AuditReplayCommand unbindCommand = new AuditReplayCommand(
                "E", "e5_device_unbind", Map.of("deviceId", 42));
        AuditOperationProposalRequest unbindRequest = new AuditOperationProposalRequest(
                "client unbind copy", "42", "client before", "client after", "growth", "growth",
                "sos", false, true, "client gate", "unbind device after manual review", "E5", unbindCommand,
                new AuditLockTarget("E", "device", "42"), null);

        var unbindPermission = guard.validateProposal(unbindCommand);
        var unbind = guard.validateProposalContext(unbindRequest);

        assertThat(unbindPermission.getCode()).isZero();
        assertThat(unbind.getCode()).isZero();
        assertThat(unbind.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::afterValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies)
                .containsExactly("解绑设备资产 · 42", "UNBOUND", false);
    }

    @Test
    void delegatedH8SettlementBindsThePcProposalToTheCanonicalFundBatchContext() {
        authenticate("platform_a2_proposal_create", "growth_h8_settle");
        AuditReplayCommand command = new AuditReplayCommand(
                "H", "h8_referral_settlement", Map.of(
                        "limit", 20,
                        "expectedH8Version", 7L,
                        "expectedRhythmMonth", 8,
                        "rewardSnapshotHash", "a".repeat(64)));
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "client supplied copy", "待结算邀请批次", "client before", "client after", "h8-maker", "growth",
                "fund", true, false, "门槛者", "核对 H8 奖励快照后申请结算", "H8", command,
                new AuditLockTarget("H", "referral_settlement_batch", "pending"), null);

        assertThat(guard.validateProposal(command).getCode()).isZero();
        var canonical = guard.validateProposalContext(request);

        assertThat(canonical.getCode()).isZero();
        assertThat(canonical.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::afterValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::operationType,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::amplifies)
                .containsExactly("执行邀请奖励真实结算", "待结算邀请批次", "最多结算 20 条", "H8", "fund", true);
        assertThat(canonical.getData().target())
                .isEqualTo(new AuditLockTarget("H", "referral_settlement_batch", "pending"));
    }

    @Test
    void delegatedH8SettlementFailsClosedForMalformedSnapshotOrContextSpoofing() {
        authenticate("platform_a2_proposal_create", "growth_h8_settle");
        AuditReplayCommand malformed = new AuditReplayCommand(
                "H", "h8_referral_settlement", Map.of(
                        "limit", 101,
                        "expectedH8Version", 7L,
                        "expectedRhythmMonth", 8,
                        "rewardSnapshotHash", "not-a-sha256"));
        AuditOperationProposalRequest malformedRequest = new AuditOperationProposalRequest(
                "client supplied copy", "待结算邀请批次", "client before", "client after", "h8-maker", "growth",
                "fund", true, false, "门槛者", "malformed snapshot must not enter A2", "H8", malformed,
                new AuditLockTarget("H", "referral_settlement_batch", "pending"), null);

        assertThat(guard.validateProposal(malformed).getCode()).isZero();
        assertThat(guard.validateProposalContext(malformedRequest).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");

        AuditReplayCommand valid = new AuditReplayCommand(
                "H", "h8_referral_settlement", Map.of(
                        "limit", 1,
                        "expectedH8Version", 7L,
                        "expectedRhythmMonth", 8,
                        "rewardSnapshotHash", "b".repeat(64)));
        AuditOperationProposalRequest spoofedTarget = new AuditOperationProposalRequest(
                "client supplied copy", "待结算邀请批次", "client before", "client after", "h8-maker", "growth",
                "fund", true, false, "门槛者", "target must remain the pending batch", "H8", valid,
                new AuditLockTarget("D", "ledger", "all"), null);

        assertThat(guard.validateProposalContext(spoofedTarget).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_MISMATCH");
    }

    @Test
    void e6UsesExactWritePermissionAndGrowthMayOnlyProposeTheFlag() {
        AuditReplayCommand coefficient = new AuditReplayCommand(
                "E", "e6_compute_config", Map.of(
                        "paramKey", "E.compute.h5BaseFactor", "value", "0.7"));
        authenticate("device_e6_read");
        var denied = guard.validateProposal(coefficient);
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).endsWith("device_e6_write");

        when(roleResolver.resolveCode()).thenReturn("GROWTH");
        authenticate("platform_a2_proposal_create", "device_e6_flag_toggle");
        AuditReplayCommand flag = new AuditReplayCommand(
                "E", "e6_compute_config", Map.of(
                        "paramKey", "E.compute.computeShareEnabled", "value", "off"));
        assertThat(guard.validateProposal(flag).getCode()).isZero();
        assertThat(guard.validateProposal(coefficient).getCode()).isEqualTo(403);

        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "client copy", "E.compute.computeShareEnabled", "on", "off", "growth", "growth",
                "param", false, true, "gate", "disable unshipped entry", "E6", flag,
                new AuditLockTarget("E", "e6_compute_config", "E.compute.computeShareEnabled"), null);
        var canonical = guard.validateProposalContext(request);
        assertThat(canonical.getCode()).isZero();
        assertThat(canonical.getData())
                .extracting(
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::action,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::objectId,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::afterValue,
                        AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor::sourceDomain)
                .containsExactly("关闭电脑共享算力入口", "E.compute.computeShareEnabled", "off", "E6");
    }

    @Test
    void delegatedE6BatchUsesServerCanonicalSortedTargetsAndFailsClosedForMaliciousKeys() {
        authenticate("platform_a2_proposal_create", "device_e6_write");
        Map<String, Object> values = Map.of(
                "E.compute.download.zhGuide", "新说明",
                "E.compute.download.enTitle", "New title");
        AuditReplayCommand command = new AuditReplayCommand(
                "E", "e6_compute_config_batch", Map.of("values", values));
        List<AuditLockTarget> canonicalTargets = List.of(
                new AuditLockTarget("E", "e6_compute_config", "E.compute.download.enTitle"),
                new AuditLockTarget("E", "e6_compute_config", "E.compute.download.zhGuide"));
        AuditOperationProposalRequest valid = new AuditOperationProposalRequest(
                "client copy",
                "E.compute.download.enTitle,E.compute.download.zhGuide",
                "client before",
                "client after",
                "e6-maker",
                "custom",
                "param",
                false,
                false,
                "gate",
                "update localized download copy",
                "E6",
                command,
                null,
                canonicalTargets);

        assertThat(guard.validateProposal(command).getCode()).isZero();
        var canonical = guard.validateProposalContext(valid);
        assertThat(canonical.getCode()).isZero();
        assertThat(canonical.getData().target()).isNull();
        assertThat(canonical.getData().objectId())
                .isEqualTo("E.compute.download.enTitle,E.compute.download.zhGuide");

        AuditOperationProposalRequest duplicateTarget = new AuditOperationProposalRequest(
                valid.action(), valid.obj(), valid.beforeValue(), valid.afterValue(),
                valid.operator(), valid.operatorRole(), valid.type(), valid.amplifies(), valid.sos(),
                valid.roleGate(), valid.reason(), valid.sourceDomain(), command, null,
                List.of(canonicalTargets.get(0), canonicalTargets.get(0), canonicalTargets.get(1)));
        assertThat(guard.validateProposalContext(duplicateTarget).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_MISMATCH");

        AuditOperationProposalRequest crossDomainTarget = new AuditOperationProposalRequest(
                valid.action(), valid.obj(), valid.beforeValue(), valid.afterValue(),
                valid.operator(), valid.operatorRole(), valid.type(), valid.amplifies(), valid.sos(),
                valid.roleGate(), valid.reason(), valid.sourceDomain(), command, null,
                List.of(
                        new AuditLockTarget("D", "e6_compute_config", "E.compute.download.enTitle"),
                        canonicalTargets.get(1)));
        assertThat(guard.validateProposalContext(crossDomainTarget).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_MISMATCH");

        AuditReplayCommand malicious = new AuditReplayCommand(
                "E",
                "e6_compute_config_batch",
                Map.of("values", Map.of(
                        "E.compute.download.zhGuide", "safe",
                        "D.finance.dailyLimit", "999999")));
        AuditOperationProposalRequest maliciousRequest = new AuditOperationProposalRequest(
                valid.action(),
                "D.finance.dailyLimit,E.compute.download.zhGuide",
                valid.beforeValue(),
                valid.afterValue(),
                valid.operator(),
                valid.operatorRole(),
                valid.type(),
                valid.amplifies(),
                valid.sos(),
                valid.roleGate(),
                valid.reason(),
                valid.sourceDomain(),
                malicious,
                null,
                List.of(
                        new AuditLockTarget("D", "finance_config", "D.finance.dailyLimit"),
                        new AuditLockTarget("E", "e6_compute_config", "E.compute.download.zhGuide")));
        assertThat(guard.validateProposalContext(maliciousRequest).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");

        AuditReplayCommand empty = new AuditReplayCommand(
                "E", "e6_compute_config_batch", Map.of("values", Map.of()));
        AuditOperationProposalRequest emptyRequest = new AuditOperationProposalRequest(
                valid.action(), "", valid.beforeValue(), valid.afterValue(),
                valid.operator(), valid.operatorRole(), valid.type(), valid.amplifies(), valid.sos(),
                valid.roleGate(), valid.reason(), valid.sourceDomain(), empty, null, List.of());
        assertThat(guard.validateProposalContext(emptyRequest).getMessage())
                .isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");
    }

    @Test
    void delegatedAccountStatusMustMatchTheExecutableOperationAndUsesOneCanonicalLockId() {
        authenticate("platform_a2_proposal_create", "user_c2_account_freeze");
        AuditReplayCommand mismatchedCommand = new AuditReplayCommand(
                "C", "c2_account_freeze", Map.of("userId", "001", "status", "ACTIVE"));
        AuditOperationProposalRequest mismatched = new AuditOperationProposalRequest(
                "freeze", "1", "ACTIVE", "FROZEN", "risk-user", "risk", "acct", false, true,
                "gate", "freeze suspicious account", "C2", mismatchedCommand,
                new AuditLockTarget("C", "user", "1"), null);

        var denied = guard.validateProposalContext(mismatched);

        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).isEqualTo("A2_BUSINESS_CONTEXT_UNMAPPED");

        AuditReplayCommand canonicalCommand = new AuditReplayCommand(
                "C", "c2_account_freeze", Map.of("userId", "001", "status", "FROZEN"));
        AuditOperationProposalRequest canonical = new AuditOperationProposalRequest(
                "freeze", "1", "ACTIVE", "FROZEN", "risk-user", "risk", "acct", false, true,
                "gate", "freeze suspicious account", "C2", canonicalCommand,
                new AuditLockTarget("C", "user", "1"), null);

        var allowed = guard.validateProposalContext(canonical);

        assertThat(allowed.getCode()).isZero();
        assertThat(allowed.getData().objectId()).isEqualTo("1");
        assertThat(allowed.getData().target().id()).isEqualTo("1");
    }

    @Test
    void fullWriterC2CommandStillUsesCanonicalDisplayObjectAndLock() {
        authenticate("platform_a2_write", "user_c2_account_freeze");
        AuditReplayCommand command = new AuditReplayCommand(
                "C", "c2_account_freeze", Map.of("userId", 1, "status", "FROZEN"));
        AuditOperationProposalRequest mismatched = new AuditOperationProposalRequest(
                "freeze user 99", "99", "ACTIVE", "FROZEN", "superadmin", "superadmin", "acct",
                false, true, "gate", "freeze suspicious account", "C2", command,
                new AuditLockTarget("C", "user", "99"), null);

        var denied = guard.validateProposalContext(mismatched);

        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).isEqualTo("A2_BUSINESS_CONTEXT_MISMATCH");
    }

    @Test
    void delegatedVariableParametersAreValidatedAndShownInCanonicalApprovalCopy() {
        authenticate("platform_a2_proposal_create", "user_c2_impersonate_start", "user_c2_blocklist_add");
        AuditReplayCommand impersonation = new AuditReplayCommand(
                "C", "c2_impersonate_start", Map.of("userId", 7, "ttlMinutes", "015"));
        AuditOperationProposalRequest validImpersonation = new AuditOperationProposalRequest(
                "impersonate", "7", "none", "session", "risk", "risk", "acct", false, true,
                "gate", "investigate suspicious account", "C2", impersonation,
                new AuditLockTarget("C", "user", "7"), null);

        var impersonationResult = guard.validateProposalContext(validImpersonation);

        assertThat(impersonationResult.getCode()).isZero();
        assertThat(impersonationResult.getData().afterValue()).isEqualTo("只读会话 · 15 分钟");

        for (Object invalidTtl : List.of(4, 31, "not-a-number")) {
            AuditReplayCommand invalid = new AuditReplayCommand(
                    "C", "c2_impersonate_start", Map.of("userId", 7, "ttlMinutes", invalidTtl));
            AuditOperationProposalRequest invalidRequest = new AuditOperationProposalRequest(
                    "impersonate", "7", "none", "session", "risk", "risk", "acct", false, true,
                    "gate", "investigate suspicious account", "C2", invalid,
                    new AuditLockTarget("C", "user", "7"), null);
            assertThat(guard.validateProposalContext(invalidRequest).getCode()).isEqualTo(403);
        }

        AuditReplayCommand permanentBlock = new AuditReplayCommand(
                "C", "c2_blocklist_upsert", Map.of("userId", 8, "kind", "BLOCK", "expiresAt", "PERMANENT"));
        AuditOperationProposalRequest blockRequest = new AuditOperationProposalRequest(
                "block", "8", "none", "blocked", "risk", "risk", "acct", false, true,
                "gate", "block confirmed abusive account", "C2", permanentBlock,
                new AuditLockTarget("C", "accountlist", "8"), null);
        assertThat(guard.validateProposalContext(blockRequest).getData().afterValue()).isEqualTo("禁入 · 长期");

        AuditReplayCommand invalidExpiry = new AuditReplayCommand(
                "C", "c2_blocklist_upsert", Map.of("userId", 8, "kind", "BLOCK", "expiresAt", "tomorrow"));
        AuditOperationProposalRequest invalidExpiryRequest = new AuditOperationProposalRequest(
                "block", "8", "none", "blocked", "risk", "risk", "acct", false, true,
                "gate", "block confirmed abusive account", "C2", invalidExpiry,
                new AuditLockTarget("C", "accountlist", "8"), null);
        assertThat(guard.validateProposalContext(invalidExpiryRequest).getCode()).isEqualTo(403);

        AuditReplayCommand pastExpiry = new AuditReplayCommand(
                "C", "c2_blocklist_upsert", Map.of("userId", 8, "kind", "BLOCK", "expiresAt", "2000-01-01"));
        AuditOperationProposalRequest pastExpiryRequest = new AuditOperationProposalRequest(
                "block", "8", "none", "blocked", "risk", "risk", "acct", false, true,
                "gate", "block confirmed abusive account", "C2", pastExpiry,
                new AuditLockTarget("C", "accountlist", "8"), null);
        assertThat(guard.validateProposalContext(pastExpiryRequest).getCode()).isEqualTo(403);
    }
}
