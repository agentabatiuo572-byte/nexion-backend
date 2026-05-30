package ffdd.commerce.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductCreateRequest {
    @NotBlank
    @Size(max = 64)
    private String productNo;

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotBlank
    @Size(max = 32)
    private String productType;

    @Size(max = 32)
    private String tier;

    @NotBlank
    @Size(max = 32)
    private String status;

    @NotNull
    @DecimalMin(value = "0.000001")
    private BigDecimal priceUsdt;

    @DecimalMin(value = "0")
    private BigDecimal hashrate;

    @DecimalMin(value = "0")
    private BigDecimal estimatedDailyUsdt;

    @DecimalMin(value = "0")
    private BigDecimal dailyNex;

    @NotNull
    @Min(0)
    private Integer stock;

    @Size(max = 512)
    private String coverUrl;

    @Size(max = 2048)
    private String detailImageUrls;
}
