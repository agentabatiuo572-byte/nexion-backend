package ffdd.opsconsole.content.dto;

import java.util.List;

public record ConversationArchiveBatchRequest(
        List<String> conversationNos,
        String reason,
        String operator) {
}
