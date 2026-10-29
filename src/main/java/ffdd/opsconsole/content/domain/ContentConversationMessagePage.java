package ffdd.opsconsole.content.domain;

import java.util.List;

/** A bounded customer transcript window, with command metadata independent of unloaded message bodies. */
public record ContentConversationMessagePage(ContentConversationView conversation,
        List<ContentConversationMessageView> messages,boolean hasMore,Long nextBeforeMessageId,
        CustomerIdentity customerIdentity,ReplyContext replyContext,List<ReadContext> readContexts) {
    public ContentConversationMessagePage {messages=List.copyOf(messages);readContexts=List.copyOf(readContexts);}
    public record CustomerIdentity(long customerId,String userNo,String nickname,String phoneMasked) { }
    public record ReplyTarget(String conversationNo,long throughMessageId) { }
    public record ReplyContext(boolean ready,List<ReplyTarget> targets) {
        public ReplyContext {targets=List.copyOf(targets);}
    }
    public record ReadContext(String conversationNo,String status,long version,long lastSeenMessageId,boolean alreadyRead) { }
}
