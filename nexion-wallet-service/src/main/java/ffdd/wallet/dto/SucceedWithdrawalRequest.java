package ffdd.wallet.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SucceedWithdrawalRequest {
    @Size(max = 128)
    private String chainTxHash;
}
