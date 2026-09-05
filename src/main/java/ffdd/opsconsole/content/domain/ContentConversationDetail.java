package ffdd.opsconsole.content.domain;

import java.util.List;

public record ContentConversationDetail(
        ContentConversationView conversation,
        List<ContentConversationMessageView> messages,
        ConversationCustomerProfile customerProfile,
        boolean historyTruncated,
        Long nextCursor) {
    public ContentConversationDetail(
            ContentConversationView conversation,
            List<ContentConversationMessageView> messages,
            ConversationCustomerProfile customerProfile) {
        this(conversation, messages, customerProfile, false, null);
    }

    public ContentConversationDetail(ContentConversationView conversation,
            List<ContentConversationMessageView> messages, ConversationCustomerProfile customerProfile,
            boolean historyTruncated) {
        this(conversation, messages, customerProfile, historyTruncated, null);
    }
}
