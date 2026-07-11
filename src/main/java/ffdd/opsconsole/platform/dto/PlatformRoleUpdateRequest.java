package ffdd.opsconsole.platform.dto;

/** A6 编辑角色元数据（不含 roleCode，避免破坏 UK+外键）。字段可空=不改。 */
public record PlatformRoleUpdateRequest(
        String roleName,
        String remark,
        Integer status,
        String reason,
        String operator) {
}
