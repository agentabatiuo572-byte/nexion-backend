package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConversationRepository {
    Map<String, Object> counters();

    PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request);

    Optional<ContentConversationView> findByConversationNo(String conversationNo);

    List<Map<String, Object>> transferTargets();

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
}
