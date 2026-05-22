package ffdd.compute.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_compute_receipt")
public class ComputeReceipt extends BaseEntity {
    private Long userId;
    private Long userDeviceId;
    private String taskNo;
    private String receiptNo;
    private String taskType;
    private String clientName;
    private BigDecimal rewardUsdt;
    private BigDecimal rewardNex;
    private String earningStatus;
    private String proofHash;
    private LocalDateTime completedAt;
}
