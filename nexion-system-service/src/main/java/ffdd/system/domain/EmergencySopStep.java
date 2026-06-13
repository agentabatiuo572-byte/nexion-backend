package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_emergency_sop_step")
public class EmergencySopStep extends BaseEntity {
    private String sopId;
    private Integer stepOrder;
    private String stepTitle;
    private String status;
    private String statusReason;
    private String operator;
    private LocalDateTime operatedAt;
}
