package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateWithdrawalRequest {
    private Long userId;

    @Size(max = 64)
    private String withdrawalNo;

    @NotBlank
    private String asset;

    @Size(max = 32)
    private String chain;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal amount;

    @NotBlank
    @Size(max = 128)
    private String targetAddress;
}
