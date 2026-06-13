package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateNexLockRequest {
    private Long userId;

    @NotNull
    @DecimalMin("1")
    private BigDecimal amountNex;

    private Integer termMonths;
}
