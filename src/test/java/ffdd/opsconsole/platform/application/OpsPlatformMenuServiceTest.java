package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import ffdd.opsconsole.platform.dto.PlatformMenuNodeCreateRequest;
import ffdd.opsconsole.platform.dto.PlatformMenuNodeUpdateRequest;
import ffdd.opsconsole.platform.infrastructure.AdminMenuEntity;
import ffdd.opsconsole.platform.mapper.AdminMenuMapper;
import ffdd.opsconsole.platform.mapper.AdminPermissionMapper;
import ffdd.opsconsole.platform.mapper.AdminRoleMenuMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsPlatformMenuServiceTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyDictionaryProducesAnEmptyTreeWithoutSyntheticMenus() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        when(menuMapper.selectAllActive()).thenReturn(List.of());
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, mock(AdminRoleMenuMapper.class), mock(AdminPermissionMapper.class),
                mock(AuditLogService.class), passThroughIdempotency());

        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().tree()).isEmpty();
        assertThat(result.getData().domainCount()).isZero();
    }

    @Test
    void menuAuditUsesAuthenticatedAdminInsteadOfSpoofedBodyOperator() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(menuMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            AdminMenuEntity entity = invocation.getArgument(0);
            entity.setId(91L);
            return 1;
        }).when(menuMapper).insert(any(AdminMenuEntity.class));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("41", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", "alice.admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, mock(AdminRoleMenuMapper.class), mock(AdminPermissionMapper.class),
                auditLogService, passThroughIdempotency());

        var result = service.createNode("idem-menu-create",
                new PlatformMenuNodeCreateRequest("Z9", "Test Menu", "测试菜单", null,
                        "/test", null, 99, "create menu for test", "mallory"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("alice.admin");
    }

    @Test
    void deleteRejectsMenuStillReferencedByActivePermissions() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AdminRoleMenuMapper roleMenuMapper = mock(AdminRoleMenuMapper.class);
        AdminPermissionMapper permissionMapper = mock(AdminPermissionMapper.class);
        AdminMenuEntity menu = new AdminMenuEntity();
        menu.setId(91L);
        menu.setMenuCode("A9");
        menu.setMenuName("Reserved");
        menu.setStatus(1);
        menu.setIsDeleted(0);
        when(menuMapper.selectActiveForUpdate(91L)).thenReturn(menu);
        when(menuMapper.selectChildren(91L)).thenReturn(List.of());
        when(permissionMapper.countActiveByMenuId(91L)).thenReturn(2L);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, roleMenuMapper, permissionMapper, mock(AuditLogService.class), passThroughIdempotency());

        var result = service.deleteNode(91L, "idem-delete-menu",
                new ffdd.opsconsole.platform.dto.AdminAccountActionRequest("remove unused menu", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("MENU_NODE_HAS_PERMISSIONS");
        verify(menuMapper, never()).updateById(any(AdminMenuEntity.class));
        verify(roleMenuMapper, never()).countActiveByMenuId(91L);
    }

    @Test
    void deactivateRejectsMenuWithActiveChildren() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AdminRoleMenuMapper roleMenuMapper = mock(AdminRoleMenuMapper.class);
        AdminPermissionMapper permissionMapper = mock(AdminPermissionMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AdminMenuEntity parent = activeMenu(91L, "A9", null);
        AdminMenuEntity child = activeMenu(92L, "A9_1", 91L);
        when(menuMapper.selectActiveForUpdate(91L)).thenReturn(parent);
        when(menuMapper.selectChildren(91L)).thenReturn(List.of(child));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("41", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", "alice.admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, roleMenuMapper, permissionMapper, auditLogService, passThroughIdempotency());

        var result = service.updateNode(91L, "idem-disable-parent",
                new PlatformMenuNodeUpdateRequest(null, null, null, null, null,
                        0, "disable parent with child", "mallory"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("MENU_NODE_HAS_ACTIVE_CHILDREN");
        assertThat(parent.getStatus()).isEqualTo(1);
        verify(menuMapper, never()).updateById(any(AdminMenuEntity.class));
        verify(permissionMapper, never()).countActiveByMenuId(91L);
        verify(roleMenuMapper, never()).countActiveByMenuId(91L);
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("A7_MENU_MUTATION_REJECTED");
        assertThat(audit.getValue().getResourceId()).isEqualTo("91");
        assertThat(audit.getValue().getActorUsername()).isEqualTo("alice.admin");
        assertThat(audit.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(audit.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(audit.getValue().getDetail()).isInstanceOfSatisfying(Map.class, detail -> {
            assertThat(detail).containsEntry("operation", "UPDATE_STATUS");
            assertThat(detail).containsEntry("rejectionCode", "MENU_NODE_HAS_ACTIVE_CHILDREN");
            assertThat(detail).containsEntry("idempotencyKey", "idem-disable-parent");
            assertThat(detail).containsEntry("reason", "disable parent with child");
        });
    }

    @Test
    void deactivateRejectsMenuWithActivePermissionReferences() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AdminRoleMenuMapper roleMenuMapper = mock(AdminRoleMenuMapper.class);
        AdminPermissionMapper permissionMapper = mock(AdminPermissionMapper.class);
        AdminMenuEntity menu = activeMenu(91L, "A9", null);
        when(menuMapper.selectActiveForUpdate(91L)).thenReturn(menu);
        when(menuMapper.selectChildren(91L)).thenReturn(List.of());
        when(permissionMapper.countActiveByMenuId(91L)).thenReturn(1L);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, roleMenuMapper, permissionMapper, mock(AuditLogService.class), passThroughIdempotency());

        var result = service.updateNode(91L, "idem-disable-permission-menu",
                updateStatus(0, "disable permission menu"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("MENU_NODE_HAS_PERMISSIONS");
        assertThat(menu.getStatus()).isEqualTo(1);
        verify(menuMapper, never()).updateById(any(AdminMenuEntity.class));
        verify(roleMenuMapper, never()).countActiveByMenuId(91L);
    }

    @Test
    void deactivateRejectsMenuWithActiveRoleBindings() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AdminRoleMenuMapper roleMenuMapper = mock(AdminRoleMenuMapper.class);
        AdminPermissionMapper permissionMapper = mock(AdminPermissionMapper.class);
        AdminMenuEntity menu = activeMenu(91L, "A9", null);
        when(menuMapper.selectActiveForUpdate(91L)).thenReturn(menu);
        when(menuMapper.selectChildren(91L)).thenReturn(List.of());
        when(permissionMapper.countActiveByMenuId(91L)).thenReturn(0L);
        when(roleMenuMapper.countActiveByMenuId(91L)).thenReturn(2L);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, roleMenuMapper, permissionMapper, mock(AuditLogService.class), passThroughIdempotency());

        var result = service.updateNode(91L, "idem-disable-role-menu",
                updateStatus(0, "disable role bound menu"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("MENU_NODE_HAS_ROLE_BINDINGS");
        assertThat(menu.getStatus()).isEqualTo(1);
        verify(menuMapper, never()).updateById(any(AdminMenuEntity.class));
    }

    @Test
    void activateRejectsChildWhoseParentIsDisabled() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AdminMenuEntity child = activeMenu(92L, "A9_1", 91L);
        child.setStatus(0);
        AdminMenuEntity parent = activeMenu(91L, "A9", null);
        parent.setStatus(0);
        when(menuMapper.selectActiveForUpdate(92L)).thenReturn(child);
        when(menuMapper.selectActiveForUpdate(91L)).thenReturn(parent);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, mock(AdminRoleMenuMapper.class), mock(AdminPermissionMapper.class),
                mock(AuditLogService.class), passThroughIdempotency());

        var result = service.updateNode(92L, "idem-enable-child",
                updateStatus(1, "enable child under parent"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("PARENT_MENU_NOT_ACTIVE");
        assertThat(child.getStatus()).isZero();
        verify(menuMapper, never()).updateById(any(AdminMenuEntity.class));
    }

    @Test
    void deleteRejectsMenuWithActiveRoleBindingsWithoutRevokingThem() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AdminRoleMenuMapper roleMenuMapper = mock(AdminRoleMenuMapper.class);
        AdminPermissionMapper permissionMapper = mock(AdminPermissionMapper.class);
        AdminMenuEntity menu = activeMenu(91L, "A9", null);
        when(menuMapper.selectActiveForUpdate(91L)).thenReturn(menu);
        when(menuMapper.selectChildren(91L)).thenReturn(List.of());
        when(permissionMapper.countActiveByMenuId(91L)).thenReturn(0L);
        when(roleMenuMapper.countActiveByMenuId(91L)).thenReturn(1L);
        AuditLogService auditLogService = mock(AuditLogService.class);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, roleMenuMapper, permissionMapper, auditLogService, passThroughIdempotency());

        var result = service.deleteNode(91L, "idem-delete-role-menu",
                new ffdd.opsconsole.platform.dto.AdminAccountActionRequest("delete role bound menu", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("MENU_NODE_HAS_ROLE_BINDINGS");
        assertThat(menu.getIsDeleted()).isZero();
        verify(menuMapper, never()).updateById(any(AdminMenuEntity.class));
        verify(roleMenuMapper).countActiveByMenuId(91L);
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(audit.capture());
        assertThat(audit.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(audit.getValue().getDetail()).isInstanceOfSatisfying(Map.class, detail -> {
            assertThat(detail).containsEntry("operation", "DELETE");
            assertThat(detail).containsEntry("rejectionCode", "MENU_NODE_HAS_ROLE_BINDINGS");
        });
    }

    @Test
    void rejectionAuditFailureFailsClosedWithoutChangingMenu() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AdminPermissionMapper permissionMapper = mock(AdminPermissionMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AdminMenuEntity parent = activeMenu(91L, "A9", null);
        AdminMenuEntity child = activeMenu(92L, "A9_1", 91L);
        when(menuMapper.selectActiveForUpdate(91L)).thenReturn(parent);
        when(menuMapper.selectChildren(91L)).thenReturn(List.of(child));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService).recordRequiredInNewTransaction(any(AuditLogWriteRequest.class));
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, mock(AdminRoleMenuMapper.class), permissionMapper,
                auditLogService, passThroughIdempotency());

        assertThatThrownBy(() -> service.updateNode(91L, "idem-disable-parent",
                updateStatus(0, "disable parent with child")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(parent.getStatus()).isEqualTo(1);
        verify(menuMapper, never()).updateById(any(AdminMenuEntity.class));
    }

    @Test
    void menuDeleteRejectsReasonShorterThanEightCharactersBeforeReadingMenu() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, mock(AdminRoleMenuMapper.class), mock(AdminPermissionMapper.class),
                mock(AuditLogService.class), passThroughIdempotency());

        var result = service.deleteNode(91L, "idem-short-reason",
                new ffdd.opsconsole.platform.dto.AdminAccountActionRequest("short", "superadmin"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
        verify(menuMapper, never()).selectActiveForUpdate(any(Long.class));
    }

    private AdminIdempotencyService passThroughIdempotency() {
        AdminIdempotencyService service = mock(AdminIdempotencyService.class);
        when(service.execute(any(String.class), any(String.class), any(String.class),
                any(Class.class), any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        return service;
    }

    private PlatformMenuNodeUpdateRequest updateStatus(int status, String reason) {
        return new PlatformMenuNodeUpdateRequest(null, null, null, null, null,
                status, reason, "superadmin");
    }

    private AdminMenuEntity activeMenu(long id, String code, Long parentId) {
        AdminMenuEntity menu = new AdminMenuEntity();
        menu.setId(id);
        menu.setMenuCode(code);
        menu.setMenuName(code);
        menu.setParentId(parentId);
        menu.setStatus(1);
        menu.setIsDeleted(0);
        return menu;
    }
}
