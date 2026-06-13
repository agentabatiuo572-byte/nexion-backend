package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserTwoFactorAdminRequest {
    @NotBlank
    @Size(max = 64)
    private String operator;

    @NotBlank
    @Size(max = 255)
    private String reason;
}
