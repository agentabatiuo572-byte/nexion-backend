package ffdd.opsconsole.platform.dto;

import java.util.List;

/** A7 菜单整树（内存构树，86 节点不分页）+ 统计。 */
public record PlatformMenuTreeOverview(
        List<MenuTreeNode> tree,
        int domainCount,
        int pageCount,
        int activeCount) {

    public record MenuTreeNode(MenuNodeView node, List<MenuTreeNode> children) {
    }

    public record MenuNodeView(
            Long id,
            String menuCode,
            String menuName,
            String menuNameZh,
            Long parentId,
            String routePath,
            String icon,
            Integer sortOrder,
            Integer status) {
    }
}
