package ffdd.opsconsole.platform.dto;

/** A7 编辑菜单节点元数据（不含 menuCode，避免破坏 UK+外键）。字段可空=不改。 */
public record PlatformMenuNodeUpdateRequest(
        String menuName,
        String menuNameZh,
        String routePath,
        String icon,
        Integer sortOrder,
        Integer status,
        String reason,
        String operator) {
}
