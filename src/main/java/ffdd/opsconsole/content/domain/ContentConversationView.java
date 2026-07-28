package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record ContentConversationView(
        Long id,
        String conversationNo,
        Long userId,
        String conversationType,
        String status,
        String ownerAgentId,
        String ownerAgentName,
        Integer unreadCount,
        String lastMessage,
        LocalDateTime lastMessageAt,
        String transferFromAgentId,
        String transferFromAgentName,
        String transferToType,
        String transferToId,
        String transferToName,
        String transferReason,
        LocalDateTime transferredAt,
        LocalDateTime updatedAt,
        Long version) {
    public ContentConversationView(
            Long id,
            String conversationNo,
            Long userId,
            String conversationType,
            String status,
            String ownerAgentId,
            String ownerAgentName,
            Integer unreadCount,
            String lastMessage,
            LocalDateTime lastMessageAt,
            String transferFromAgentId,
            String transferFromAgentName,
            String transferToType,
            String transferToId,
            String transferToName,
            String transferReason,
            LocalDateTime transferredAt,
            LocalDateTime updatedAt) {
        this(id, conversationNo, userId, conversationType, status, ownerAgentId, ownerAgentName,
                unreadCount, lastMessage, lastMessageAt, transferFromAgentId, transferFromAgentName,
                transferToType, transferToId, transferToName, transferReason, transferredAt, updatedAt, 0L);
    }
}
