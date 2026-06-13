package ffdd.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PriceIndexRequest {
    @NotBlank
    private String metricCode;
    @NotBlank
    private String metricLabel;
    @NotBlank
    private String unitLabel;
    @NotNull
    private BigDecimal priceUsdt;
    private BigDecimal deltaPercent;
    private BigDecimal volume24hUsdt;
    private String sparkline;
    private String status;
    private LocalDateTime sampledAt;
}
