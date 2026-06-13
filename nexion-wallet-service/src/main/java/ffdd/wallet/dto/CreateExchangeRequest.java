package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateExchangeRequest {
    private Long userId;

    @Size(max = 64)
    private String exchangeNo;

    @NotBlank
    private String fromAsset;

    @NotBlank
    private String toAsset;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal fromAmount;
}
