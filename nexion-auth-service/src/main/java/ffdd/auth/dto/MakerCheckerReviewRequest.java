package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MakerCheckerReviewRequest {
    @NotBlank
    private String checker;
    @NotBlank
    private String reason;
}
