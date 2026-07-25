package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record ConversationIdleCandidate(
        Long id,
        String conversationNo,
        String status,
        LocalDateTime lastActivityAt) {
}
