package ffdd.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RegisterSmsCodeResponse {
    private Integer expiresInSeconds;
}
