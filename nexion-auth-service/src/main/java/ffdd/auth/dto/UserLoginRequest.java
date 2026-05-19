package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UserLoginRequest {
    @NotBlank
    private String countryCode;
    @NotBlank
    @Pattern(regexp = "\\d{6,15}")
    private String phone;
    @NotBlank
    private String password;
}

