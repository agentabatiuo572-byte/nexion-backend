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
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "Password must be at least 8 characters and include letters and numbers")
    private String password;
    @NotBlank
    private String verificationCode;
    private String referralCode;
}
