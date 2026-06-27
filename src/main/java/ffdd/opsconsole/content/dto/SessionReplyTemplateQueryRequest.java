package ffdd.opsconsole.content.dto;

public record SessionReplyTemplateQueryRequest(
        String type,
        String status,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
