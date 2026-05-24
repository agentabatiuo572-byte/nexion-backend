package ffdd.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ManualRiskReviewRequest {
    @NotBlank
    @Size(max = 64)
    private String reviewer;

    @NotBlank
    @Size(max = 255)
    private String reason;
}
