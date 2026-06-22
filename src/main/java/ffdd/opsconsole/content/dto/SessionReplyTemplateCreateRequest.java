package ffdd.opsconsole.content.dto;

public record SessionReplyTemplateCreateRequest(
        String type,
        String text,
        String status,
        String operator,
        String reason) {
}
