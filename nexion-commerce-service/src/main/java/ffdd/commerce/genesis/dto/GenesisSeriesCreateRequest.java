package ffdd.commerce.genesis.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class GenesisSeriesCreateRequest {
    @NotBlank
    @Size(max = 64)
    private String seriesCode;

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotNull
    @Min(1)
    private Integer totalSupply;

    @NotNull
    @DecimalMin(value = "0.000001")
    private BigDecimal priceUsdt;

    @Size(max = 32)
    private String status;

    private LocalDateTime saleStartAt;
    private LocalDateTime saleEndAt;

    @Min(0)
    private Integer royaltyBps;

    @Size(max = 512)
    private String coverUrl;

    @Size(max = 2048)
    private String metadataJson;
}
