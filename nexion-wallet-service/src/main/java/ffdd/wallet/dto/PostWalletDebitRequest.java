package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class PostWalletDebitRequest {
    @NotNull
    private Long userId;

    @NotBlank
    private String bizNo;

    @NotBlank
    private String bizType;

    @NotBlank
    private String asset;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal amount;

    private String remark;
}
