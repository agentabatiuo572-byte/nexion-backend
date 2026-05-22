package ffdd.team.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class OrderPaidPayload {
    private String orderNo;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal amountUsdt;
    private String paymentNo;
    private LocalDateTime paidAt;
}
