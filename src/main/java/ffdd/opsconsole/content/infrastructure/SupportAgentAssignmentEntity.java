package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_support_agent_user_assignment")
public class SupportAgentAssignmentEntity extends BaseEntity {
    private Long agentAdminId;
    private Long userId;
    private String status;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String operator;
    private String reason;
}
