package ffdd.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserImpersonationStartRequest {
    @NotBlank
    @Size(max = 64)
    private String operator;

    @NotBlank
    @Size(max = 255)
    private String reason;

    @Min(1)
    @Max(30)
    private Integer ttlMinutes = 30;
}
