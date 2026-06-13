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
    private String userLevelName;
    private String nextUserLevel;
    private String nextUserLevelName;
    private Integer userLevelProgressPercent;
    private String vRank;
    private String status;
    private String language;
    private String region;
    private String bio;
    private String timezone;
    private Boolean soundEnabled;
    private Boolean hapticsEnabled;
    private Boolean notifyCommission;
    private Boolean notifyTeam;
    private Boolean notifyStaking;
    private Boolean notifyMarket;
    private Boolean notifyGenesis;
    private Boolean notifySystem;
    private String walletAddress;
    private Boolean walletPaired;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
