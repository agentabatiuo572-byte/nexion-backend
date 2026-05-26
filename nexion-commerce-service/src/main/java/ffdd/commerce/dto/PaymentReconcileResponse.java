package ffdd.commerce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReconcileResponse {
    private String provider;
    private String paymentNo;
    private String orderNo;
    private String providerStatus;
    private String paymentStatus;
    private String orderPaymentStatus;
    private String activationStatus;
    private boolean changed;
    private String message;
}
