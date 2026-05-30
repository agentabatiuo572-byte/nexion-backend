package ffdd.auth.dto;

import lombok.Data;

@Data
public class UserQueryRequest {
    private String phone;
    private String nickname;
    private String referralCode;
    private String status;
    private String kycStatus;
    private String userLevel;
    private String vRank;
}
