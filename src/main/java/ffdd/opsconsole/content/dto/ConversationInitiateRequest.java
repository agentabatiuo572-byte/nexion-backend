package ffdd.opsconsole.content.dto;

public record ConversationInitiateRequest(
        String conversationType,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long userId,
        String ownerAgentId,
        String ownerAgentName,
        String openingText,
        String reason,
        String operator,
        String kind,String attachmentId,String intent,String clientMessageId,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedAssignmentId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        java.util.List<ConversationReplyRequest.ReplyTarget> replyTargets,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String skuId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) SupportLinkTarget linkTarget) {
    public ConversationInitiateRequest(String conversationType,Long userId,String ownerAgentId,String ownerAgentName,
            String openingText,String reason,String operator,String kind,String attachmentId,String intent,
            String clientMessageId,Long expectedAssignmentId,java.util.List<ConversationReplyRequest.ReplyTarget> replyTargets) {
        this(conversationType,userId,ownerAgentId,ownerAgentName,openingText,reason,operator,kind,attachmentId,intent,clientMessageId,expectedAssignmentId,replyTargets,null,null);
    }
    @Override public String toString() {
        if(kind!=null || attachmentId!=null || intent!=null || clientMessageId!=null || expectedAssignmentId!=null || replyTargets!=null || skuId!=null || linkTarget!=null)
            return SupportMessagePayload.encode(this);
        String base="ConversationInitiateRequest[conversationType="+conversationType+", userId="+userId+", ownerAgentId="+ownerAgentId
            +", ownerAgentName="+ownerAgentName+", openingText="+openingText+", reason="+reason+", operator="+operator;
        return base+(kind==null && attachmentId==null && intent==null && clientMessageId==null && expectedAssignmentId==null ? "]"
            : ", kind="+kind+", attachmentId="+attachmentId+", intent="+intent+", clientMessageId="+clientMessageId+", expectedAssignmentId="+expectedAssignmentId+"]");
    }
    public ConversationInitiateRequest(String conversationType,Long userId,String ownerAgentId,String ownerAgentName,
            String openingText,String reason,String operator) {
        this(conversationType,userId,ownerAgentId,ownerAgentName,openingText,reason,operator,null,null,null,null,null);
    }
    public ConversationInitiateRequest(String conversationType,Long userId,String ownerAgentId,String ownerAgentName,
            String openingText,String reason,String operator,String kind,String attachmentId,String intent,
            String clientMessageId,Long expectedAssignmentId) {
        this(conversationType,userId,ownerAgentId,ownerAgentName,openingText,reason,operator,kind,attachmentId,intent,
                clientMessageId,expectedAssignmentId,null);
    }
}
