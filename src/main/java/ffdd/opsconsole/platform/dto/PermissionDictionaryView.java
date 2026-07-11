package ffdd.opsconsole.platform.dto;

/** A8 权限字典展示视图（只读）。 */
public record PermissionDictionaryView(
        String permissionCode,
        String permissionName,
        String permType,
        Long menuId,
        String menuCodePath,
        Integer amplifies,
        Integer boundRoleCount,
        String resourcePath) {
}
