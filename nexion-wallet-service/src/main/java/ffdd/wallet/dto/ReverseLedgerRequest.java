package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReverseLedgerRequest {
    @NotBlank
    private String reason;
}
