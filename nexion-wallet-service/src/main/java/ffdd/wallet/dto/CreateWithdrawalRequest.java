package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateWithdrawalRequest {
    @NotNull
    private Long userId;

    @NotBlank
    private String withdrawalNo;

    @NotBlank
    private String asset;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal amount;

    @DecimalMin("0.000000")
    private BigDecimal fee;

    @NotBlank
    @Size(max = 128)
    private String targetAddress;
}
