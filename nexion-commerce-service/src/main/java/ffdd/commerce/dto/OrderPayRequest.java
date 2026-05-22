package ffdd.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OrderPayRequest {
    @NotBlank
    private String paymentNo;
}
