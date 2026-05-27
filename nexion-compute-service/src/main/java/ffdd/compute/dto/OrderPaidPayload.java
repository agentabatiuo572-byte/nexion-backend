package ffdd.compute.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class OrderPaidPayload {
    private String orderNo;
    private Long userId;
    private Long productId;
    private String productTier;
    private String productName;
    private String deviceType;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private Integer quantity;
    private BigDecimal amountUsdt;
    private String paymentNo;
    private LocalDateTime paidAt;
}
