package ffdd.opsconsole.content.domain;

/** I1 文案版本配置目录。versionKey 是稳定业务键，创建后不可变。 */
public record CopyVersionOptionView(
        String versionKey,
        String name,
        String description,
        String status,
        Integer sortOrder,
        Long revision,
        Long usageCount) {

    public CopyVersionOptionView(
            String versionKey, String name, String description, String status, Integer sortOrder, Long revision) {
        this(versionKey, name, description, status, sortOrder, revision, 0L);
    }
}
