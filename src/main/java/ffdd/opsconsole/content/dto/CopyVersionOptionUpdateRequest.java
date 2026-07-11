package ffdd.opsconsole.content.dto;

/** versionKey 仅由路径传入且不可修改。 */
public record CopyVersionOptionUpdateRequest(
        String name,
        String description,
        String status,
        Integer sortOrder,
        Long expectedRevision,
        String operator,
        String reason) {
}
