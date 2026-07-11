package ffdd.opsconsole.platform.dto;

import java.util.List;

/** A6 单角色详情（含当前权限/菜单勾选）。 */
public record PlatformRoleDetail(
        Long id,
        String roleCode,
        String roleName,
        String remark,
        Integer status,
        boolean builtin,
        List<String> permissionCodes,
        List<Long> menuIds) {
}
