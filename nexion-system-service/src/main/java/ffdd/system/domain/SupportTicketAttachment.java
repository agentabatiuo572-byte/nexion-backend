package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_support_ticket_attachment")
public class SupportTicketAttachment extends BaseEntity {
    private Long ticketId;
    private Long messageId;
    private String objectKey;
    private String fileName;
    private String contentType;
    private Long fileSize;
}
