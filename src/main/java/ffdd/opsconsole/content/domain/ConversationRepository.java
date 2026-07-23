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

    boolean markAgentMessagesReadThrough(String conversationNo, Long lastSeenMessageId, String operator, LocalDateTime now);

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

    boolean returnTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now);

    boolean waitTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now);

    boolean reply(ContentConversationView conversation, String body, String operator, LocalDateTime now);

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
}
