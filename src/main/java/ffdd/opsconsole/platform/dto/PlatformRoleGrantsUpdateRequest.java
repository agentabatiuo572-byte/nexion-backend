package ffdd.opsconsole.platform.dto;

import java.util.List;

/** A6 改角色权限/菜单绑定（核心 HIGH）。permissionCodes + menuIds 为全量白名单（同步差量）。 */
public record PlatformRoleGrantsUpdateRequest(
        List<String> permissionCodes,
        List<Long> menuIds,
        String reason,
        String operator) {
}
