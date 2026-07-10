package ffdd.opsconsole.content.domain;

/** 文案位置槽位视图(配置表 nx_content_copy_position 的只读投影)。 */
public record CopyPositionView(
        String positionKey,
        String name,
        String surface,
        Integer sortOrder,
        String status) {
}
