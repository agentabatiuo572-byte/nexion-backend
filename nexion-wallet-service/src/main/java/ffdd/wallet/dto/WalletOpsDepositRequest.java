package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class WalletOpsDepositRequest {
    @NotNull
    private Long userId;

    @NotBlank
    @Size(max = 32)
    private String chain;

    @NotBlank
    @Size(max = 128)
    private String chainTxHash;

    @NotBlank
    @Size(max = 16)
    private String asset;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal amount;

    private Integer confirmations;

    @Size(max = 255)
    private String reason;
}
