package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SubmitWithdrawalChainRequest {
    @NotBlank
    @Size(max = 128)
    private String chainTxHash;
}
