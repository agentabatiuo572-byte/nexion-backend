package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("nx_support_human_message")
public class SupportHumanMessageEntity {
    @TableId private Long messageId;
    private Long customerId;
    private Long assignmentId;
    private String actorType;
    private Long actorId;
    private String clientMessageId;
    private String kind;
    private String intent;
    private String attachmentId;
    private java.time.LocalDateTime committedAt;
    private String payloadHash;
}
