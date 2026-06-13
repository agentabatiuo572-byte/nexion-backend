package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateStakingPositionRequest {
    private Long userId;

    @NotNull
    private Long productId;

    @NotNull
    @DecimalMin("1")
    private BigDecimal amountUsdt;
}
