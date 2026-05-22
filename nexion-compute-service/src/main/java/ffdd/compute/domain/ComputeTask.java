package ffdd.compute.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_compute_task")
public class ComputeTask extends BaseEntity {
    private String taskNo;
    private Long userId;
    private Long userDeviceId;
    private String taskType;
    private String clientName;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
