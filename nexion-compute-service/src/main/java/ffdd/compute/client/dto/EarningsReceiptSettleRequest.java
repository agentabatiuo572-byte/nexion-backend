package ffdd.compute.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EarningsReceiptSettleRequest {
    private Long userId;
    private Long userDeviceId;
    private String receiptNo;
    private BigDecimal rewardUsdt;
    private BigDecimal rewardNex;
    private LocalDateTime completedAt;
}
