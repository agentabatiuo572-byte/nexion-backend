package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ContentConversationDetail(
        ContentConversationView conversation,
        List<ContentConversationMessageView> messages,
        ConversationCustomerProfile customerProfile,
        boolean historyTruncated,
        Long nextCursor,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ConversationWriteState writeState) {
    public ContentConversationDetail(
            ContentConversationView conversation,
            List<ContentConversationMessageView> messages,
            ConversationCustomerProfile customerProfile) {
        this(conversation, messages, customerProfile, false, null, null);
    }

    public ContentConversationDetail(ContentConversationView conversation,
            List<ContentConversationMessageView> messages, ConversationCustomerProfile customerProfile,
            boolean historyTruncated) {
        this(conversation, messages, customerProfile, historyTruncated, null, null);
    }

    public ContentConversationDetail(ContentConversationView conversation,
            List<ContentConversationMessageView> messages, ConversationCustomerProfile customerProfile,
            boolean historyTruncated, Long nextCursor) {
        this(conversation, messages, customerProfile, historyTruncated, nextCursor, null);
    }
}
