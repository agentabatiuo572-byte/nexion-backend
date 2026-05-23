package ffdd.compute.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ComputeTaskCompletedPayload {
    private Long userId;
    private Long userDeviceId;
    private String taskNo;
    private String receiptNo;
    private String taskType;
    private String clientName;
    private BigDecimal rewardUsdt;
    private BigDecimal rewardNex;
    private LocalDateTime completedAt;
    private String proofHash;
}
