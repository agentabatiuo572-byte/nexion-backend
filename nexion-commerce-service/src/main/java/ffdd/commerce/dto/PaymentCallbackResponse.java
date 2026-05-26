package ffdd.commerce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCallbackResponse {
    private String provider;
    private String providerEventId;
    private String paymentNo;
    private String orderNo;
    private String paymentStatus;
    private String orderPaymentStatus;
    private String activationStatus;
    private boolean duplicate;
    private String message;
}
