package ffdd.opsconsole.content.dto;

public record ConversationReplyRequest(
        String body,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator, java.util.List<ReplyTarget> replyTargets, Long replyThroughMessageId) {
    public record ReplyTarget(String conversationNo, Long throughMessageId) {}
    public ConversationReplyRequest(String body,String expectedStatus,Long expectedVersion,String reason,String operator) {
        this(body,expectedStatus,expectedVersion,reason,operator,null,null);
    }
    public ConversationReplyRequest(String body, String reason, String operator) {
        this(body, "OPEN", 0L, reason, operator);
    }
}
