package ffdd.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.AdminMenu;
import ffdd.auth.domain.AdminPermission;
import ffdd.auth.domain.AdminRole;
import ffdd.auth.dto.AssignRoleMenusRequest;
import ffdd.auth.dto.AssignRolePermissionsRequest;
import ffdd.auth.dto.MenuCreateRequest;
import ffdd.auth.dto.MenuQueryRequest;
import ffdd.auth.dto.MenuUpdateRequest;
import ffdd.auth.dto.PermissionCreateRequest;
import ffdd.auth.dto.PermissionQueryRequest;
import ffdd.auth.dto.PermissionUpdateRequest;
import ffdd.auth.dto.RoleCreateRequest;
import ffdd.auth.dto.RoleQueryRequest;
import ffdd.auth.dto.RoleUpdateRequest;
import ffdd.auth.service.AccessControlService;
import ffdd.common.api.ApiResult;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/access-control")
@RequiredArgsConstructor
public class AccessControlController {
    private final AccessControlService accessControlService;

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('PERM_ROLE_WRITE')")
    public ApiResult<AdminRole> createRole(@Valid @RequestBody RoleCreateRequest request) {
        return ApiResult.ok(accessControlService.createRole(request));
    }

    @GetMapping("/roles/page")
    @PreAuthorize("hasAuthority('PERM_ROLE_READ')")
    public ApiResult<Page<AdminRole>> rolePage(@ModelAttribute RoleQueryRequest query,
                                               @RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "20") long size) {
        return ApiResult.ok(accessControlService.rolePage(current, size, query));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('PERM_ROLE_WRITE')")
    public ApiResult<AdminRole> updateRole(@PathVariable Long id, @Valid @RequestBody RoleUpdateRequest request) {
        return ApiResult.ok(accessControlService.updateRole(id, request));
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('PERM_ROLE_WRITE')")
    public ApiResult<Void> deleteRole(@PathVariable Long id) {
        accessControlService.deleteRole(id);
        return ApiResult.ok();
    }

    @PostMapping("/menus")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_WRITE')")
    public ApiResult<AdminMenu> createMenu(@Valid @RequestBody MenuCreateRequest request) {
        return ApiResult.ok(accessControlService.createMenu(request));
    }

    @GetMapping("/menus")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_READ')")
    public ApiResult<List<AdminMenu>> menuList(@ModelAttribute MenuQueryRequest query) {
        return ApiResult.ok(accessControlService.menuList(query));
    }

    @PutMapping("/menus/{id}")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_WRITE')")
    public ApiResult<AdminMenu> updateMenu(@PathVariable Long id, @Valid @RequestBody MenuUpdateRequest request) {
        return ApiResult.ok(accessControlService.updateMenu(id, request));
    }

    @DeleteMapping("/menus/{id}")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_WRITE')")
    public ApiResult<Void> deleteMenu(@PathVariable Long id) {
        accessControlService.deleteMenu(id);
        return ApiResult.ok();
    }

    @GetMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('PERM_ROLE_READ')")
    public ApiResult<List<Long>> permissionIds(@PathVariable Long id) {
        return ApiResult.ok(accessControlService.permissionIds(id));
    }

    @GetMapping("/roles/{id}/menus")
    @PreAuthorize("hasAuthority('PERM_ROLE_READ')")
    public ApiResult<List<Long>> menuIds(@PathVariable Long id) {
        return ApiResult.ok(accessControlService.menuIds(id));
    }

    @PutMapping("/roles/{id}/menus")
    @PreAuthorize("hasAuthority('PERM_ROLE_PERMISSION_ASSIGN')")
    public ApiResult<Void> assignMenus(@PathVariable Long id, @Valid @RequestBody AssignRoleMenusRequest request) {
        accessControlService.assignMenus(id, request.getMenuIds());
        return ApiResult.ok();
    }

    @PutMapping("/roles/{id}/api-permissions")
    @PreAuthorize("hasAuthority('PERM_ROLE_PERMISSION_ASSIGN')")
    public ApiResult<Void> assignApiPermissions(@PathVariable Long id, @Valid @RequestBody AssignRolePermissionsRequest request) {
        accessControlService.assignApiPermissions(id, request.getPermissionIds());
        return ApiResult.ok();
    }

    @PostMapping("/permissions")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_WRITE')")
    public ApiResult<AdminPermission> createPermission(@Valid @RequestBody PermissionCreateRequest request) {
        return ApiResult.ok(accessControlService.createPermission(request));
    }

    @GetMapping("/permissions/page")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_READ')")
    public ApiResult<Page<AdminPermission>> permissionPage(@ModelAttribute PermissionQueryRequest query,
                                                          @RequestParam(defaultValue = "1") long current,
                                                          @RequestParam(defaultValue = "20") long size) {
        return ApiResult.ok(accessControlService.permissionPage(current, size, query));
    }

    @PutMapping("/permissions/{id}")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_WRITE')")
    public ApiResult<AdminPermission> updatePermission(@PathVariable Long id,
                                                       @Valid @RequestBody PermissionUpdateRequest request) {
        return ApiResult.ok(accessControlService.updatePermission(id, request));
    }

    @DeleteMapping("/permissions/{id}")
    @PreAuthorize("hasAuthority('PERM_PERMISSION_WRITE')")
    public ApiResult<Void> deletePermission(@PathVariable Long id) {
        accessControlService.deletePermission(id);
        return ApiResult.ok();
    }
}
