package ffdd.opsconsole.platform.dto;

/** A8 权限字典列表查询参数（@ModelAttribute 绑定 query string）。 */
public record PermissionDictionaryQueryRequest(
        String keyword,
        String domain,
        String permType,
        Integer pageNum,
        Integer pageSize) {
}
