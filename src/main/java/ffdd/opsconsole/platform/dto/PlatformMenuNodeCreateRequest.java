package ffdd.opsconsole.platform.dto;

/** A7 新建菜单节点。menuCode 创建后不可改；parentCode 空=顶级域。 */
public record PlatformMenuNodeCreateRequest(
        String menuCode,
        String menuName,
        String menuNameZh,
        String parentCode,
        String routePath,
        String icon,
        Integer sortOrder,
        String reason,
        String operator) {
}
