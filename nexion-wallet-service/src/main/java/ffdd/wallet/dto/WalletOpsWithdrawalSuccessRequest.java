package ffdd.wallet.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WalletOpsWithdrawalSuccessRequest {
    @Size(max = 128)
    private String chainTxHash;

    @Size(max = 255)
    private String reason;
}
