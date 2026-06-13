package ffdd.commerce.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class TradeinRuleRequest {
    @Size(max = 64)
    private String sourceProductNo;

    @Size(max = 32)
    private String sourceTier;

    @NotBlank
    @Size(max = 32)
    private String targetTier;

    @DecimalMin(value = "0")
    private BigDecimal discountUsdt;

    @DecimalMin(value = "0")
    private BigDecimal salvageRate;

    @Min(0)
    private Integer minHoldingMonths;

    private Integer status;

    private Integer sortOrder;
}
