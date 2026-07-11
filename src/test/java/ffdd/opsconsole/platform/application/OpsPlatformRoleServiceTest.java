package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.platform.dto.PlatformRoleGrantsUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleCreateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.infrastructure.AdminRoleEntity;
import ffdd.opsconsole.platform.mapper.AdminRoleMapper;
import ffdd.opsconsole.platform.mapper.AdminRoleMenuMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsPlatformRoleServiceTest {
    private final AdminRoleMapper roleMapper = mock(AdminRoleMapper.class);
    private final AdminRoleMenuMapper roleMenuMapper = mock(AdminRoleMenuMapper.class);
    private final AdminRolePermissionMapper rolePermissionMapper = mock(AdminRolePermissionMapper.class);
    private final AdminRoleRelationMapper roleRelationMapper = mock(AdminRoleRelationMapper.class);
    private final AdminPermissionCache permissionCache = mock(AdminPermissionCache.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsAuditCenterService auditCenterService = mock(OpsAuditCenterService.class);
    private final OpsPlatformRoleService service = new OpsPlatformRoleService(
            roleMapper, roleMenuMapper, rolePermissionMapper, roleRelationMapper,
            permissionCache, auditLogService, auditCenterService);

    @AfterEach
    void clearReplay() {
        A2ReplayContext.exitReplay();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRoleAuditUsesAuthenticatedAdminInsteadOfSpoofedOperator() {
        authenticate("41", "alice.admin");
        doAnswer(invocation -> {
            AdminRoleEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            return 1;
        }).when(roleMapper).insert(any(AdminRoleEntity.class));
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 1));
        when(rolePermissionMapper.selectActivePermissionCodesByRole(9L)).thenReturn(List.of());
        when(roleMenuMapper.selectActiveMenuIdsByRole(9L)).thenReturn(List.of());

        ApiResult<?> result = service.createRole("idem-create",
                new PlatformRoleCreateRequest("CUSTOM_OPS", "Custom Ops", null, 1,
                        "create empty role", "mallory"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("alice.admin");
    }

    @Test
    void metadataOnlyRoleUpdateAuditUsesAuthenticatedAdminInsteadOfSpoofedOperator() {
        authenticate("41", "alice.admin");
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 1));
        when(roleRelationMapper.selectAdminIdsByRole(9L)).thenReturn(List.of());
        when(rolePermissionMapper.selectActivePermissionCodesByRole(9L)).thenReturn(List.of());
        when(roleMenuMapper.selectActiveMenuIdsByRole(9L)).thenReturn(List.of());

        ApiResult<?> result = service.updateRole(9L, "idem-metadata",
                new PlatformRoleUpdateRequest("Renamed", "new remark", null,
                        "rename role", "mallory"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("alice.admin");
    }

    @Test
    void roleDisableCreatesA2ProposalWithoutMutatingOrEvicting() {
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 1));
        when(auditCenterService.createProposal(eq("idem-role-status"), any())).thenReturn(ApiResult.ok(null));

        ApiResult<?> result = service.updateRole(9L, "idem-role-status",
                new PlatformRoleUpdateRequest(null, null, 0, "disable compromised role", "superadmin"));

        assertThat(result.getCode()).isZero();
        verify(auditCenterService).createProposal(eq("idem-role-status"), any());
        verify(roleMapper, never()).updateById(any(AdminRoleEntity.class));
        verify(permissionCache, never()).evict(any());
    }

    @Test
    void roleEnableAlsoCreatesA2ProposalBecauseItRestoresTheAuthorizationSurface() {
        authenticate("41", "alice.admin");
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 0));
        when(auditCenterService.createProposal(eq("idem-role-enable"), any())).thenReturn(ApiResult.ok(null));

        ApiResult<?> result = service.updateRole(9L, "idem-role-enable",
                new PlatformRoleUpdateRequest(null, null, 1, "restore role", "superadmin"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditOperationProposalRequest> proposal =
                ArgumentCaptor.forClass(AuditOperationProposalRequest.class);
        verify(auditCenterService).createProposal(eq("idem-role-enable"), proposal.capture());
        assertThat(proposal.getValue().command().op()).isEqualTo("a6_role_status_update");
        assertThat(proposal.getValue().command().params()).containsEntry("status", 1);
        assertThat(proposal.getValue().type()).isEqualTo("acct");
        assertThat(proposal.getValue().operator()).isEqualTo("alice.admin");
        verify(roleMapper, never()).updateById(any(AdminRoleEntity.class));
    }

    @Test
    void mixedRoleMetadataAndStatusChangeIsRejectedAsOneAmbiguousMutation() {
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 0));

        ApiResult<?> result = service.updateRole(9L, "idem-mixed",
                new PlatformRoleUpdateRequest("Renamed", null, 1, "mixed mutation", "superadmin"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("ROLE_STATUS_UPDATE_MUST_BE_SEPARATE");
        verify(auditCenterService, never()).createProposal(any(), any());
        verify(roleMapper, never()).updateById(any(AdminRoleEntity.class));
    }

    @Test
    void approvedRoleDisableReplayEvictsEveryAssignedAdmin() {
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 1));
        when(roleRelationMapper.selectAdminIdsByRole(9L)).thenReturn(List.of(31L, 32L));
        when(rolePermissionMapper.selectActivePermissionCodesByRole(9L)).thenReturn(List.of());
        when(roleMenuMapper.selectActiveMenuIdsByRole(9L)).thenReturn(List.of());
        A2ReplayContext.enterReplay();

        ApiResult<?> result = service.updateRole(9L, "idem-role-status",
                new PlatformRoleUpdateRequest(null, null, 0, "approved disable", "approver"));

        assertThat(result.getCode()).isZero();
        verify(permissionCache).evict(31L);
        verify(permissionCache).evict(32L);
    }

    @Test
    void roleDeleteCreatesA2ProposalAndLeavesRelationsUntouched() {
        authenticate("41", "alice.admin");
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 1));
        when(auditCenterService.createProposal(eq("idem-delete"), any())).thenReturn(ApiResult.ok(null));

        ApiResult<?> result = service.deleteRole(9L, "idem-delete",
                new ffdd.opsconsole.platform.dto.AdminAccountActionRequest("remove obsolete role", "superadmin"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditOperationProposalRequest> proposal =
                ArgumentCaptor.forClass(AuditOperationProposalRequest.class);
        verify(auditCenterService).createProposal(eq("idem-delete"), proposal.capture());
        assertThat(proposal.getValue().command().op()).isEqualTo("a6_role_delete");
        assertThat(proposal.getValue().target().type()).isEqualTo("a6_role");
        assertThat(proposal.getValue().target().id()).isEqualTo("9");
        assertThat(proposal.getValue().type()).isEqualTo("acct");
        assertThat(proposal.getValue().operator()).isEqualTo("alice.admin");
        verify(roleRelationMapper, never()).disableRelationsByRole(9L);
        verify(roleMapper, never()).updateById(any(AdminRoleEntity.class));
    }

    @Test
    void superAdminRoleCannotBeDisabled() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "SUPER_ADMIN", 1));

        ApiResult<?> result = service.updateRole(1L, "idem-disable-super",
                new PlatformRoleUpdateRequest(null, null, 0, "unsafe", "superadmin"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("SUPER_ADMIN_ROLE_IMMUTABLE");
        verify(roleMapper, never()).updateById(any(AdminRoleEntity.class));
    }

    @Test
    void highRiskGrantChangeCreatesA2ProposalWithoutMutatingBindings() {
        authenticate("41", "alice.admin");
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 1));
        when(rolePermissionMapper.selectActivePermissionCodesByRole(9L)).thenReturn(List.of("user_c1_read"));
        when(roleMenuMapper.selectActiveMenuIdsByRole(9L)).thenReturn(List.of(3L));
        when(auditCenterService.createProposal(eq("idem-grants"), any())).thenReturn(ApiResult.ok(null));

        ApiResult<?> result = service.updateRoleGrants(9L, "idem-grants",
                new PlatformRoleGrantsUpdateRequest(List.of("user_c1_read", "user_c1_write"), List.of(3L),
                        "expand support", "superadmin"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditOperationProposalRequest> proposal =
                ArgumentCaptor.forClass(AuditOperationProposalRequest.class);
        verify(auditCenterService).createProposal(eq("idem-grants"), proposal.capture());
        assertThat(proposal.getValue().type()).isEqualTo("acct");
        assertThat(proposal.getValue().operator()).isEqualTo("alice.admin");
        assertThat(proposal.getValue().command().params()).containsEntry("operator", "alice.admin");
        verify(rolePermissionMapper, never()).disableRolePermissionsExcept(any(), any());
        verify(roleMenuMapper, never()).disableRoleMenusExcept(any(), any());
    }

    @Test
    void approvedReplayRestoresSoftDeletedGrantsBeforeInsertingMissingRows() {
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "CUSTOM_OPS", 1));
        when(rolePermissionMapper.selectActivePermissionCodesByRole(9L)).thenReturn(List.of());
        when(roleMenuMapper.selectActiveMenuIdsByRole(9L)).thenReturn(List.of());
        when(roleRelationMapper.selectAdminIdsByRole(9L)).thenReturn(List.of(31L));
        A2ReplayContext.enterReplay();

        ApiResult<?> result = service.updateRoleGrants(9L, "idem-replay",
                new PlatformRoleGrantsUpdateRequest(List.of("user_c1_read"), List.of(3L),
                        "approved", "approver"));

        assertThat(result.getCode()).isZero();
        verify(rolePermissionMapper).restoreRolePermissions("CUSTOM_OPS", List.of("user_c1_read"));
        verify(rolePermissionMapper).insertMissingRolePermissions("CUSTOM_OPS", List.of("user_c1_read"));
        verify(roleMenuMapper).restoreRoleMenus(9L, List.of(3L));
        verify(roleMenuMapper).insertMissingRoleMenus(9L, List.of(3L));
        verify(permissionCache).evict(31L);
    }

    private AdminRoleEntity role(Long id, String code, int status) {
        AdminRoleEntity role = new AdminRoleEntity();
        role.setId(id);
        role.setRoleCode(code);
        role.setRoleName(code);
        role.setStatus(status);
        role.setIsDeleted(0);
        return role;
    }

    private void authenticate(String adminId, String username) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(adminId, null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", username));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
