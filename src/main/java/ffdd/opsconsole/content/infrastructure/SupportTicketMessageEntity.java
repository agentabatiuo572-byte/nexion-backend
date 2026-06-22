package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_support_ticket_message")
public class SupportTicketMessageEntity extends BaseEntity {
    private Long ticketId;
    private String ticketNo;
    private Long senderId;
    private String senderType;
    private String senderName;
    private String content;
}
