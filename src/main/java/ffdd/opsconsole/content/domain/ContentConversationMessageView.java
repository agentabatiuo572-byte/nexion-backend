package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record ContentConversationMessageView(
        Long id,
        Long conversationId,
        String conversationNo,
        Long senderId,
        String senderType,
        String senderName,
        String content,
        LocalDateTime createdAt) {
}
