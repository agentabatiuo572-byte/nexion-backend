package ffdd.commerce.genesis.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class GenesisSeriesUpdateRequest {
    @Size(max = 128)
    private String name;

    @Min(1)
    private Integer totalSupply;

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

    @Size(max = 512)
    private String description;

    @Size(max = 96)
    private String dividendLabel;

    @Size(max = 96)
    private String utilityLabel;

    @Size(max = 96)
    private String rarityLabel;

    @Size(max = 8)
    private List<@Size(max = 96) String> traits;

    @Size(max = 8)
    private List<@Size(max = 255) String> mediaObjectKeys;
}
