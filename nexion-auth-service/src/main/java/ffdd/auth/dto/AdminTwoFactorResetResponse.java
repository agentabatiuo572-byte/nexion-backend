package ffdd.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminTwoFactorResetResponse {
    private Long adminId;
    private String username;
    private Boolean twoFactorEnabled;
    private String nextLoginRequirement;
}
