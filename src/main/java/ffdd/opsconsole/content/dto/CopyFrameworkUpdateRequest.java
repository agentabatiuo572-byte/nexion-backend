package ffdd.opsconsole.content.dto;

public record CopyFrameworkUpdateRequest(
        String value,
        String operator,
        String reason) {
}
