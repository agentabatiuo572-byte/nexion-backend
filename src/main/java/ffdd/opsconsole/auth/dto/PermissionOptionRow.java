package ffdd.opsconsole.auth.dto;

/** RBAC 权限选项行（权限字典下拉用），映射 nx_admin_permission 的 permission_code/permission_name。 */
public record PermissionOptionRow(String permissionCode, String permissionName) {
}
