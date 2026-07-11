package ffdd.opsconsole.content.dto;

public record CopyVersionOptionCreateRequest(
        String versionKey,
        String name,
        String description,
        String status,
        Integer sortOrder,
        String operator,
        String reason) {
}
