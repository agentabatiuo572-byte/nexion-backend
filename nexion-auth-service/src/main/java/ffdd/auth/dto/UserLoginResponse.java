package ffdd.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserLoginResponse {
    private String token;
    private Long userId;
    private String nickname;
    private String referralCode;
    private String userLevel;
    private String vRank;
}

