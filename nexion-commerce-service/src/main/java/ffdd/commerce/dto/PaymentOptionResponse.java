package ffdd.commerce.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOptionResponse {
    private String provider;
    private String label;
    private String currency;
    private String network;
    private Boolean enabled;
    private Boolean defaultOption;
    private BigDecimal minUsdt;
    private BigDecimal maxUsdt;
    private String feeMode;
    private String feeLabel;
    private BigDecimal feeAmountUsdt;
    private BigDecimal feeRatePct;
}
