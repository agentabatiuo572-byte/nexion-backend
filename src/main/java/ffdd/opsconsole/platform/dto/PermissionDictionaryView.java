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
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String description() {
        String scope = menuCodePath == null || menuCodePath.isBlank() ? "未归类页面" : menuCodePath;
        String action = permissionName == null || permissionName.isBlank() ? "该操作" : permissionName;
        return switch (permType == null ? "" : permType) {
            case "READ" -> "可在「" + scope + "」查看：" + action + "。此权限不授予写入能力。";
            case "WRITE" -> "可在「" + scope + "」发起：" + action + "。仍需满足业务条件。";
            default -> "可在「" + scope + "」发起敏感操作：" + action + "。仍需满足实际审批和业务条件。";
        };
    }
}
