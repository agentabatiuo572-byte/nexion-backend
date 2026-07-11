package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsPlatformRoleService;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleCreateRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleDetail;
import ffdd.opsconsole.platform.dto.PlatformRoleGrantsUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleOverview;
import ffdd.opsconsole.platform.dto.PlatformRoleUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A6 角色管理（角色 CRUD + 权限/菜单双绑定）。 */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/roles")
@RequiredArgsConstructor
public class OpsPlatformRoleController {
    private final OpsPlatformRoleService roleService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('platform_a6_read')")
    public ApiResult<PlatformRoleOverview> overview() {
        return roleService.overview();
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("hasAuthority('platform_a6_read')")
    public ApiResult<PlatformRoleDetail> detail(@PathVariable Long roleId) {
        return roleService.detail(roleId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform_a6_write')")
    public ApiResult<PlatformRoleDetail> create(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody PlatformRoleCreateRequest request) {
        return roleService.createRole(idempotencyKey, request);
    }

    @PatchMapping("/{roleId}")
    @PreAuthorize("hasAuthority('platform_a6_write')")
    public ApiResult<?> update(
            @PathVariable Long roleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody PlatformRoleUpdateRequest request) {
        return roleService.updateRole(roleId, idempotencyKey, request);
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('platform_a6_write')")
    public ApiResult<?> delete(
            @PathVariable Long roleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return roleService.deleteRole(roleId, idempotencyKey, request);
    }

    /** 改角色权限/菜单绑定（核心 HIGH）。 */
    @PutMapping("/{roleId}/grants")
    @PreAuthorize("hasAuthority('platform_a6_role_grants_update')")
    public ApiResult<?> grants(
            @PathVariable Long roleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody PlatformRoleGrantsUpdateRequest request) {
        return roleService.updateRoleGrants(roleId, idempotencyKey, request);
    }
}
