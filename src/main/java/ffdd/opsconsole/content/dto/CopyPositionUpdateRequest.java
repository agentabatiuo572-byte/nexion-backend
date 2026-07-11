package ffdd.opsconsole.content.dto;

public record CopyPositionUpdateRequest(
        String name,
        String surface,
        Integer sortOrder,
        String status,
        String operator,
        String reason) {
}
