package ffdd.opsconsole.content.dto;

public record SessionReplyTemplateStatusRequest(
        String status,
        String operator,
        String reason) {
}
