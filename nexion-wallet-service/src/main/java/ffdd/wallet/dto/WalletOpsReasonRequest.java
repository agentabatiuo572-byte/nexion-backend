package ffdd.wallet.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WalletOpsReasonRequest {
    @Size(max = 255)
    private String reason;
}
