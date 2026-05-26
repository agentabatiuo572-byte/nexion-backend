package ffdd.commerce.genesis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GenesisPurchaseRequest {
    @NotNull
    private Long userId;

    @NotBlank
    private String seriesCode;

    @Min(1)
    @Max(10)
    private Integer quantity = 1;

    private String clientRequestNo;
}
