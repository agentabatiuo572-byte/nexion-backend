package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserRegisterRequest {
    @NotBlank
    private String countryCode;
    @NotBlank
    @Pattern(regexp = "\\d{6,15}")
    private String phone;
    @NotBlank
    @Size(min = 6)
    private String password;
    private String referralCode;
}

