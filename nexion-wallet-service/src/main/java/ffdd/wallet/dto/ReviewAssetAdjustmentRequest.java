package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewAssetAdjustmentRequest {
    @NotBlank
    @Size(max = 16)
    private String decision;

    @NotBlank
    @Size(max = 64)
    private String checker;

    @NotBlank
    @Size(max = 255)
    private String reviewReason;
}
