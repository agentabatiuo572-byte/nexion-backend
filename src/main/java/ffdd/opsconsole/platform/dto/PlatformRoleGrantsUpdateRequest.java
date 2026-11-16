package ffdd.opsconsole.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A6 改角色权限/菜单绑定（核心 HIGH）。permissionCodes + menuIds 为全量白名单（同步差量）。
 *
 * <p>expectedPermissionCodes / expectedMenuIds 是发起人当时看到的那一组授权（读接口返回的
 * permissionCodes / menuIds）。服务端建单和批准回放都用它核对：不一致返回 409，避免用旧快照
 * 覆盖期间别人新加或收回的授权。发起方没带这两项时按缺失拒绝，不用服务端当前状态代填。
 */
public record PlatformRoleGrantsUpdateRequest(
        @Size(max = 2000) List<@NotBlank @Size(max = 128) String> permissionCodes,
        @Size(max = 2000) List<@Positive Long> menuIds,
        @NotBlank @Size(min = 8, max = 200) String reason,
        @Size(max = 128) String operator,
        @Size(max = 2000) List<@NotBlank @Size(max = 128) String> expectedPermissionCodes,
        @Size(max = 2000) List<@Positive Long> expectedMenuIds) {

    /** 旧调用方不带预期快照的构造形状；服务端按缺失处理，不会代为读取当前授权。 */
    public PlatformRoleGrantsUpdateRequest(List<String> permissionCodes, List<Long> menuIds,
            String reason, String operator) {
        this(permissionCodes, menuIds, reason, operator, null, null);
    }
}
