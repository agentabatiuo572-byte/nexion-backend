package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminTwoFactorResetRequest {
    @NotBlank
    private String operator;
    @NotBlank
    private String reason;
}
