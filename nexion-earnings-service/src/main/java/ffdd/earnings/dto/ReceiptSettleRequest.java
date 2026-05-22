package ffdd.earnings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ReceiptSettleRequest {
    @NotNull
    private Long userId;

    private Long userDeviceId;

    @NotBlank
    private String receiptNo;

    private BigDecimal rewardUsdt = BigDecimal.ZERO;
    private BigDecimal rewardNex = BigDecimal.ZERO;
    private LocalDateTime completedAt;
}
