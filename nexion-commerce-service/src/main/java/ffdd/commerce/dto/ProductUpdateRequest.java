package ffdd.commerce.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductUpdateRequest {
    @Size(max = 128)
    private String name;

    @Size(max = 32)
    private String productType;

    @Size(max = 32)
    private String tier;

    @Size(max = 32)
    private String status;

    @DecimalMin(value = "0.000001")
    private BigDecimal priceUsdt;

    @DecimalMin(value = "0")
    private BigDecimal hashrate;

    @DecimalMin(value = "0")
    private BigDecimal estimatedDailyUsdt;

    @DecimalMin(value = "0")
    private BigDecimal dailyNex;

    @Min(0)
    private Integer stock;

    @Size(max = 512)
    private String coverUrl;
}
