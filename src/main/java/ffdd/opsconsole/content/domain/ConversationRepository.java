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

    List<ContentConversationMessageView> messages(String conversationNo);

    void transferToPending(
            ContentConversationView conversation,
            String targetType,
            String targetId,
            String targetName,
            String reason,
            String operator,
            LocalDateTime now);

    void acceptTransfer(ContentConversationView conversation, String operator, LocalDateTime now);

    void returnTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now);

    void waitTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now);

    void reply(ContentConversationView conversation, String body, String operator, LocalDateTime now);

    void updateStatus(ContentConversationView conversation, String status, String operator, LocalDateTime now);

    void archive(ContentConversationView conversation, boolean archived, String operator, LocalDateTime now);

    void fallbackTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now);

    void markConvertedToTicket(ContentConversationView conversation, String ticketNo, String operator, LocalDateTime now);

    ContentConversationView createConversation(
            String conversationNo,
            Long userId,
            String conversationType,
            String ownerAgentId,
            String ownerAgentName,
            String openingText,
            LocalDateTime now);
}
