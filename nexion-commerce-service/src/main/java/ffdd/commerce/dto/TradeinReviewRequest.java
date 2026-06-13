package ffdd.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TradeinReviewRequest {
    @NotBlank
    @Size(max = 32)
    private String status;

    @Size(max = 512)
    private String reviewNote;
}
