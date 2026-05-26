package ffdd.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PaymentCheckoutRequest {
    @NotBlank
    private String orderNo;
    private String provider = "MOCK";
    private String returnUrl;
    private String notifyUrl;
}
