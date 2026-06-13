package ffdd.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionResponse {
    private Long userId;
    private String refreshTokenId;
    private String deviceName;
    private String clientIp;
    private String location;
    private Boolean twoFactorEnabled;
    private String status;
    private String createdAt;
    private String expiresAt;
    private String revokedAt;
}
