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
        String receiptStatus,
        LocalDateTime createdAt,
        Long assignmentId,String kind,String intent,String clientMessageId,String attachmentId,String authorConfidence,String committedAt,
        String skuId,String skuName,@com.fasterxml.jackson.annotation.JsonIgnore String linkTargetJson,
        @com.fasterxml.jackson.annotation.JsonIgnore String senderAvatarAssetId,@com.fasterxml.jackson.annotation.JsonIgnore Long senderAvatarVersion,
        String targetAvailability) {
    public ContentConversationMessageView(Long id,Long conversationId,String conversationNo,Long senderId,String senderType,
            String senderName,String content,String receiptStatus,LocalDateTime createdAt,Long assignmentId,String kind,String intent,
            String clientMessageId,String attachmentId,String authorConfidence,String committedAt) {
        this(id,conversationId,conversationNo,senderId,senderType,senderName,content,receiptStatus,createdAt,assignmentId,kind,intent,clientMessageId,attachmentId,authorConfidence,committedAt,null,null,null,null,null,null);
    }
    public com.fasterxml.jackson.databind.JsonNode getLinkTarget() {return ffdd.opsconsole.content.dto.SupportMessagePayload.tree(linkTargetJson);}
    public java.util.Map<String,Object> getSenderAvatar(){return senderAvatarAssetId==null || !"VERIFIED".equals(authorConfidence) || !"agent".equals(senderType)?null:java.util.Map.of("assetId",senderAvatarAssetId,"version",senderAvatarVersion==null?0L:senderAvatarVersion);}
    @org.apache.ibatis.annotations.AutomapConstructor
    public ContentConversationMessageView {
        kind=kind==null?"TEXT":kind;intent=intent==null?"SERVICE":intent;
        authorConfidence=authorConfidence==null?"UNKNOWN":authorConfidence;
    }
    public String transcriptText() {
        return "IMAGE".equals(kind)?"图片（在来源会话查看）"+(content==null || content.isBlank()?"":" "+content):content;
    }
    public ContentConversationMessageView(Long id,Long conversationId,String conversationNo,Long senderId,String senderType,
            String senderName,String content,String receiptStatus,LocalDateTime createdAt) {
        this(id,conversationId,conversationNo,senderId,senderType,senderName,content,receiptStatus,createdAt,null,"TEXT","SERVICE",null,null,"UNKNOWN",null);
    }
}
