package ffdd.opsconsole.content.dto;

public record ConversationQueryRequest(
        String status,
        String type,
        String ownerAgentId,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
