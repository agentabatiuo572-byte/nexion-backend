package ffdd.opsconsole.platform.dto;

/** A6 新建角色。roleCode 创建后不可改；与内置码同名会被拒。 */
public record PlatformRoleCreateRequest(
        String roleCode,
        String roleName,
        String remark,
        Integer status,
        String reason,
        String operator) {
}
