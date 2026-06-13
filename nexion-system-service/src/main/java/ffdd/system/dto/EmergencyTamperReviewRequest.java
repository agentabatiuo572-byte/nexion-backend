package ffdd.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmergencyTamperReviewRequest {
    @NotBlank
    private String verdict;

    @NotBlank
    @Size(max = 64)
    private String operator;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
