package ffdd.opsconsole.content.dto;

public record ConversationReplyRequest(
        String body,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator, java.util.List<ReplyTarget> replyTargets, Long replyThroughMessageId,
        String kind, String attachmentId, String intent, String clientMessageId,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedAssignmentId) {
    public record ReplyTarget(String conversationNo, Long throughMessageId) {}
    // Retained S3 command digests used record text. Preserve it for unchanged legacy requests.
    @Override public String toString() {
        if(kind!=null || attachmentId!=null || intent!=null || clientMessageId!=null || expectedAssignmentId!=null)
            return SupportMessagePayload.encode(this);
        String base="ConversationReplyRequest[body="+body+", expectedStatus="+expectedStatus+", expectedVersion="+expectedVersion
            +", reason="+reason+", operator="+operator+", replyTargets="+replyTargets+", replyThroughMessageId="+replyThroughMessageId;
        return base+(kind==null && attachmentId==null && intent==null && clientMessageId==null && expectedAssignmentId==null ? "]"
            : ", kind="+kind+", attachmentId="+attachmentId+", intent="+intent+", clientMessageId="+clientMessageId+", expectedAssignmentId="+expectedAssignmentId+"]");
    }
    public ConversationReplyRequest(String body,String expectedStatus,Long expectedVersion,String reason,String operator) {
        this(body,expectedStatus,expectedVersion,reason,operator,null,null);
    }
    public ConversationReplyRequest(String body,String expectedStatus,Long expectedVersion,String reason,String operator,
            java.util.List<ReplyTarget> replyTargets,Long replyThroughMessageId) {
        this(body,expectedStatus,expectedVersion,reason,operator,replyTargets,replyThroughMessageId,null,null,null,null,null);
    }
    public ConversationReplyRequest(String body, String reason, String operator) {
        this(body, "OPEN", 0L, reason, operator);
    }
}
