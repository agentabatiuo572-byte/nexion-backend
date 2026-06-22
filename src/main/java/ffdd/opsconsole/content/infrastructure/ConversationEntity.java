package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_conversation")
public class ConversationEntity extends BaseEntity {
    private String conversationNo;
    private Long userId;
    private String conversationType;
    private String status;
    private String ownerAgentId;
    private String ownerAgentName;
    private Integer unreadCount;
    private String lastMessage;
    private LocalDateTime lastMessageAt;
}
