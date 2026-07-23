package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.application.DisclosureContentHash;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
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
    private final AuditReplayBusinessPermissionGuard guard =
            new AuditReplayBusinessPermissionGuard(repository, roleResolver);

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

    private AuditReplayCommand sectionCommand(String sectionKey, String action) {
        return new AuditReplayCommand("I", "i4_trust_section_manage", Map.of(
                "sectionKey", sectionKey, "action", action));
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "proposer", "n/a", java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
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
