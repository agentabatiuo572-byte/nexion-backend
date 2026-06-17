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
        LocalDateTime updatedAt) {
}
