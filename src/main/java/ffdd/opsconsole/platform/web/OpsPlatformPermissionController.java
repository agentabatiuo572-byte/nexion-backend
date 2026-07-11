package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsPlatformPermissionDictionaryService;
import ffdd.opsconsole.platform.dto.PermissionDictionaryQueryRequest;
import ffdd.opsconsole.platform.dto.PermissionDictionaryView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A8 权限字典（只读）。 */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/permissions")
@RequiredArgsConstructor
public class OpsPlatformPermissionController {
    private final OpsPlatformPermissionDictionaryService permissionService;

    @GetMapping
    @PreAuthorize("hasAuthority('platform_a8_read')")
    public ApiResult<PageResult<PermissionDictionaryView>> list(@ModelAttribute PermissionDictionaryQueryRequest query) {
        return permissionService.list(query);
    }

    @GetMapping("/{permissionCode}")
    @PreAuthorize("hasAuthority('platform_a8_read')")
    public ApiResult<PermissionDictionaryView> detail(@PathVariable String permissionCode) {
        return permissionService.detail(permissionCode);
    }
}
