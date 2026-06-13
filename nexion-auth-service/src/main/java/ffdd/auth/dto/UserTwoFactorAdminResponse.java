package ffdd.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserTwoFactorAdminResponse {
    private Long userId;
    private Boolean twoFactorEnabled;
    private String operator;
    private String reason;
    private String updatedAt;
}
