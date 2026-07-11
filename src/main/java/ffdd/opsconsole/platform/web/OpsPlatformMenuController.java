package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsPlatformMenuService;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.PlatformMenuNodeCreateRequest;
import ffdd.opsconsole.platform.dto.PlatformMenuNodeUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformMenuTreeOverview;
import ffdd.opsconsole.platform.dto.PlatformMenuTreeOverview.MenuNodeView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A7 菜单管理（树 CRUD）。 */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/menus")
@RequiredArgsConstructor
public class OpsPlatformMenuController {
    private final OpsPlatformMenuService menuService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('platform_a7_read')")
    public ApiResult<PlatformMenuTreeOverview> overview() {
        return menuService.overview();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform_a7_write')")
    public ApiResult<MenuNodeView> create(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody PlatformMenuNodeCreateRequest request) {
        return menuService.createNode(idempotencyKey, request);
    }

    @PatchMapping("/{menuId}")
    @PreAuthorize("hasAuthority('platform_a7_write')")
    public ApiResult<MenuNodeView> update(
            @PathVariable Long menuId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody PlatformMenuNodeUpdateRequest request) {
        return menuService.updateNode(menuId, idempotencyKey, request);
    }

    @DeleteMapping("/{menuId}")
    @PreAuthorize("hasAuthority('platform_a7_write')")
    public ApiResult<Void> delete(
            @PathVariable Long menuId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AdminAccountActionRequest request) {
        return menuService.deleteNode(menuId, idempotencyKey, request);
    }
}
