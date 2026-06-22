package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_support_ticket")
public class SupportTicketEntity extends BaseEntity {
    private String ticketNo;
    private Long userId;
    private String category;
    private String priority;
    private String status;
    private String title;
    private String lastMessage;
    private Long assignedAdminId;
    private String assignedAdminName;
    private Integer userUnreadCount;
    private Integer opsUnreadCount;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime closedAt;
}
