package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateExchangeRequest {
    @NotNull
    private Long userId;

    @NotBlank
    private String exchangeNo;

    @NotBlank
    private String fromAsset;

    @NotBlank
    private String toAsset;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal fromAmount;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal toAmount;

    @NotNull
    @DecimalMin("0.00000001")
    private BigDecimal rate;
}
