package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_support_ticket_message")
public class SupportTicketMessage extends BaseEntity {
    private Long ticketId;
    private String ticketNo;
    private Long senderId;
    private String senderType;
    private String senderName;
    private String content;
}
