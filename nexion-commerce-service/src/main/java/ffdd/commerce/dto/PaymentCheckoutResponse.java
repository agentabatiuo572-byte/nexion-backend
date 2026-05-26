package ffdd.commerce.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCheckoutResponse {
    private String paymentNo;
    private String orderNo;
    private String provider;
    private String providerPaymentId;
    private String paymentStatus;
    private BigDecimal amountUsdt;
    private String currency;
    private String checkoutUrl;
    private LocalDateTime expiresAt;
}
