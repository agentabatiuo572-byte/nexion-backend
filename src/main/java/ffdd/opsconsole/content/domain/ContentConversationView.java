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
        Long version,
        Long lastPublicMessageId,
        String lastMessageKind,
        Boolean archived,
        Long pendingReplyCount,
        LocalDateTime pendingReplySince,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Long userClearedThroughMessageId) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public ContentConversationView(Long id, String conversationNo, Long userId, String conversationType,
            String status, String ownerAgentId, String ownerAgentName, Integer unreadCount, String lastMessage,
            LocalDateTime lastMessageAt, String transferFromAgentId, String transferFromAgentName,
            String transferToType, String transferToId, String transferToName, String transferReason,
            LocalDateTime transferredAt, LocalDateTime updatedAt, Long version, Long lastPublicMessageId,
            String lastMessageKind, Boolean archived, Long pendingReplyCount, LocalDateTime pendingReplySince) {
        this(id, conversationNo, userId, conversationType, status, ownerAgentId, ownerAgentName, unreadCount,
                lastMessage, lastMessageAt, transferFromAgentId, transferFromAgentName, transferToType,
                transferToId, transferToName, transferReason, transferredAt, updatedAt, version,
                lastPublicMessageId, lastMessageKind, archived, pendingReplyCount, pendingReplySince, null);
    }
    public ContentConversationView(Long id, String conversationNo, Long userId, String conversationType,
            String status, String ownerAgentId, String ownerAgentName, Integer unreadCount, String lastMessage,
            LocalDateTime lastMessageAt, String transferFromAgentId, String transferFromAgentName,
            String transferToType, String transferToId, String transferToName, String transferReason,
            LocalDateTime transferredAt, LocalDateTime updatedAt, Long version, Long lastPublicMessageId,
            String lastMessageKind, Boolean archived, Long pendingReplyCount) {
        this(id, conversationNo, userId, conversationType, status, ownerAgentId, ownerAgentName, unreadCount,
                lastMessage, lastMessageAt, transferFromAgentId, transferFromAgentName, transferToType,
                transferToId, transferToName, transferReason, transferredAt, updatedAt, version,
                lastPublicMessageId, lastMessageKind, archived, pendingReplyCount, null);
    }
    public ContentConversationView {
        archived = Boolean.TRUE.equals(archived);
    }
    public ContentConversationView(Long id, String conversationNo, Long userId, String conversationType,
            String status, String ownerAgentId, String ownerAgentName, Integer unreadCount, String lastMessage,
            LocalDateTime lastMessageAt, String transferFromAgentId, String transferFromAgentName,
            String transferToType, String transferToId, String transferToName, String transferReason,
            LocalDateTime transferredAt, LocalDateTime updatedAt, Long version, Long lastPublicMessageId,
            String lastMessageKind, Boolean archived) {
        this(id, conversationNo, userId, conversationType, status, ownerAgentId, ownerAgentName, unreadCount,
                lastMessage, lastMessageAt, transferFromAgentId, transferFromAgentName, transferToType,
                transferToId, transferToName, transferReason, transferredAt, updatedAt, version,
                lastPublicMessageId, lastMessageKind, archived, null);
    }
    public ContentConversationView(Long id, String conversationNo, Long userId, String conversationType,
            String status, String ownerAgentId, String ownerAgentName, Integer unreadCount, String lastMessage,
            LocalDateTime lastMessageAt, String transferFromAgentId, String transferFromAgentName,
            String transferToType, String transferToId, String transferToName, String transferReason,
            LocalDateTime transferredAt, LocalDateTime updatedAt, Long version, Long lastPublicMessageId, String lastMessageKind) {
        this(id, conversationNo, userId, conversationType, status, ownerAgentId, ownerAgentName, unreadCount,
                lastMessage, lastMessageAt, transferFromAgentId, transferFromAgentName, transferToType,
                transferToId, transferToName, transferReason, transferredAt, updatedAt, version,lastPublicMessageId,lastMessageKind,false);
    }
    public ContentConversationView(Long id, String conversationNo, Long userId, String conversationType,
            String status, String ownerAgentId, String ownerAgentName, Integer unreadCount, String lastMessage,
            LocalDateTime lastMessageAt, String transferFromAgentId, String transferFromAgentName,
            String transferToType, String transferToId, String transferToName, String transferReason,
            LocalDateTime transferredAt, LocalDateTime updatedAt, Long version, Long lastPublicMessageId) {
        this(id, conversationNo, userId, conversationType, status, ownerAgentId, ownerAgentName, unreadCount,
                lastMessage, lastMessageAt, transferFromAgentId, transferFromAgentName, transferToType,
                transferToId, transferToName, transferReason, transferredAt, updatedAt, version, lastPublicMessageId, null);
    }
    public ContentConversationView(Long id, String conversationNo, Long userId, String conversationType,
            String status, String ownerAgentId, String ownerAgentName, Integer unreadCount, String lastMessage,
            LocalDateTime lastMessageAt, String transferFromAgentId, String transferFromAgentName,
            String transferToType, String transferToId, String transferToName, String transferReason,
            LocalDateTime transferredAt, LocalDateTime updatedAt, Long version) {
        this(id, conversationNo, userId, conversationType, status, ownerAgentId, ownerAgentName, unreadCount,
                lastMessage, lastMessageAt, transferFromAgentId, transferFromAgentName, transferToType,
                transferToId, transferToName, transferReason, transferredAt, updatedAt, version, 0L);
    }
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
