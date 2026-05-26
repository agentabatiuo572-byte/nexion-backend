package ffdd.commerce.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class PaymentCallbackRequest {
    private String eventId;
    private String paymentNo;
    private String providerPaymentId;
    private String orderNo;
    private String status;
    private BigDecimal amountUsdt;
    private String currency;
    private String failureReason;
}
