package ffdd.opsconsole.content.domain;

import java.util.List;

public record ContentConversationDetail(
        ContentConversationView conversation,
        List<ContentConversationMessageView> messages) {
}
