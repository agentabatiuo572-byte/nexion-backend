package ffdd.opsconsole.content.dto;

public record NovaPoolUpdateRequest(
        Integer count,
        String operator,
        String reason) {
}
