package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConversationRepository {
    void ensureSeedData(LocalDateTime now);

    Map<String, Object> counters();

    PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request);

    Optional<ContentConversationView> findByConversationNo(String conversationNo);

    /** Locks the conversation header first and its active transfer second. */
    Optional<ContentConversationView> findByConversationNoForUpdate(String conversationNo);

    List<ContentConversationMessageView> messages(String conversationNo);

    /** Public App projection: transfer reasons and other SYSTEM traces remain ops-only. */
    default List<ContentConversationMessageView> userVisibleMessages(String conversationNo) {
        return messages(conversationNo).stream()
                .filter(message -> "user".equalsIgnoreCase(message.senderType())
                        || "agent".equalsIgnoreCase(message.senderType()))
                .toList();
    }

    /**
     * Receipt mutation is a guarded write: the header expectation is repeated in
     * the write itself even though callers hold the header lock.  This prevents a
     * late receipt from crossing a status/version transition when an alternate
     * repository implementation does not share the same transaction boundary.
     */
    boolean markAgentMessagesReadThrough(
            String conversationNo, Long lastSeenMessageId, String operator, LocalDateTime now,
            String expectedStatus, Long expectedVersion);

    List<ContentConversationView> overdueTransferredConversations(LocalDateTime cutoff, int limit);

    boolean transferToPending(
            ContentConversationView conversation,
            String targetType,
            String targetId,
            String targetName,
            String reason,
            String operator,
            LocalDateTime now);

    boolean acceptTransfer(ContentConversationView conversation, String ownerAgentId, String ownerAgentName, String operator, LocalDateTime now);

    default boolean returnTransfer(
            ContentConversationView conversation,
            String target,
            String reason,
            String operator,
            LocalDateTime now) {
        return returnTransfer(conversation, reason, operator, now);
    }

    /** Compatibility seam for older repository fakes; production overrides the explicit-target command. */
    default boolean returnTransfer(
            ContentConversationView conversation,
            String reason,
            String operator,
            LocalDateTime now) {
        throw new UnsupportedOperationException("EXPLICIT_RETURN_TARGET_REQUIRED");
    }

    boolean waitTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now);

    boolean reply(ContentConversationView conversation, String body, String operator, LocalDateTime now);

    /** Returns the exact persisted row id for SSE correlation; production overrides atomically. */
    default Long replyAndReturnMessageId(
            ContentConversationView conversation, String body, String operator, LocalDateTime now) {
        throw new UnsupportedOperationException("EXACT_MESSAGE_ID_REQUIRED");
    }

    /** App user reply, kept separate from the agent command so sender/audit semantics cannot be forged. */
    default boolean replyAsUser(ContentConversationView conversation, Long userId, String body, LocalDateTime now) {
        throw new UnsupportedOperationException("APP_CONVERSATION_REPLY_NOT_IMPLEMENTED");
    }

    boolean updateStatus(ContentConversationView conversation, String status, String operator, LocalDateTime now);

    boolean archive(ContentConversationView conversation, boolean archived, String operator, LocalDateTime now);

    boolean fallbackTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now);

    /**
     * Atomically claims a conversation for its single terminal conversion to a ticket.
     * A false result means another request already closed/converted the conversation.
     */
    boolean markConvertedToTicket(ContentConversationView conversation, String ticketNo, String operator, LocalDateTime now);

    ContentConversationView createConversation(
            String conversationNo,
            Long userId,
            String conversationType,
            String ownerAgentId,
            String ownerAgentName,
            String openingText,
            LocalDateTime now);

    /** Exact conversation + first persisted message pair used by management SSE. */
    default PersistedConversation createConversationWithMessage(
            String conversationNo,
            Long userId,
            String conversationType,
            String ownerAgentId,
            String ownerAgentName,
            String openingText,
            LocalDateTime now) {
        throw new UnsupportedOperationException("EXACT_MESSAGE_ID_REQUIRED");
    }

    record PersistedConversation(ContentConversationView conversation, Long messageId) {}

    /** Creates an inbound App conversation whose first message is authored by the authenticated user. */
    default ContentConversationView createUserConversation(
            String conversationNo,
            Long userId,
            String conversationType,
            String openingText,
            LocalDateTime now) {
        throw new UnsupportedOperationException("APP_CONVERSATION_CREATE_NOT_IMPLEMENTED");
    }
}
