package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WalletOpsWithdrawalFailureRequest {
    @NotBlank
    @Size(max = 255)
    private String reason;
}
