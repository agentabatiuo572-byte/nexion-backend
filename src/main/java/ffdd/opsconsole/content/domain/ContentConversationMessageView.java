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
        Long assignmentId,String kind,String intent,String clientMessageId,String attachmentId,String authorConfidence,String committedAt) {
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
