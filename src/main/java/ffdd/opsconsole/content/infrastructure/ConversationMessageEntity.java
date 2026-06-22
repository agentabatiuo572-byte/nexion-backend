package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_conversation_message")
public class ConversationMessageEntity extends BaseEntity {
    private Long conversationId;
    private String conversationNo;
    private Long senderId;
    private String senderType;
    private String senderName;
    private String content;
}
