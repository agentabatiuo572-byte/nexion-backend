package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nx_support_maintenance_cycle")
public class SupportMaintenanceCycleEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long customerId;
    private Long assignmentId;
    private Long agentAdminId;
    private String status;
    private Long baselineActivitySeq;
    private LocalDateTime openedAt;
    private LocalDateTime lastExecutionAt;
    private LocalDateTime closedAt;
    private Long successEventId;
}
