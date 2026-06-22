package ffdd.opsconsole.content.dto;

public record ConversationQueryRequest(
        String status,
        String type,
        String ownerAgentId,
        Long userId,
        String keyword,
        Boolean unreadOnly,
        Long pageNum,
        Long pageSize) {
}
