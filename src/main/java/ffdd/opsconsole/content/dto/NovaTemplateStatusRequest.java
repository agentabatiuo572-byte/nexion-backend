package ffdd.opsconsole.content.dto;

public record NovaTemplateStatusRequest(
        String status,
        String operator,
        String reason) {
}
