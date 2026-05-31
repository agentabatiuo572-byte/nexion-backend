package ffdd.auth.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserResponse {
    private Long id;
    private String countryCode;
    private String phone;
    private String phoneMasked;
    private String nickname;
    private String avatarUrl;
    private String referralCode;
    private Long sponsorUserId;
    private String sponsorCode;
    private String kycStatus;
    private String userLevel;
    private String vRank;
    private String status;
    private String language;
    private String region;
    private String bio;
    private String timezone;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
