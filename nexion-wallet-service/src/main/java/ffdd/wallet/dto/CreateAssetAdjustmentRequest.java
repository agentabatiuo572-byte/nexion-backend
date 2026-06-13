package ffdd.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateAssetAdjustmentRequest {
    @NotNull
    private Long userId;

    @NotBlank
    @Size(max = 16)
    private String asset;

    @NotBlank
    @Size(max = 16)
    private String direction;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal amount;

    @NotBlank
    @Size(max = 64)
    private String reasonCode;

    @NotBlank
    @Size(max = 255)
    private String reason;

    @NotBlank
    @Size(max = 64)
    private String maker;
}
