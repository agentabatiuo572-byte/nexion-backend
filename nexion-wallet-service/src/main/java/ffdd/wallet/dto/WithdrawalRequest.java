package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class WithdrawalRequest {
    @NotNull
    private BigDecimal amount;
    @NotBlank
    private String token;
    @NotBlank
    private String network;
    @NotBlank
    private String address;
}

