package ffdd.auth.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferralSponsorResponse {
    private String referralCode;
    private Long sponsorUserId;
    private String sponsorName;
    private String avatarUrl;
    private String countryCode;
    private String userLevel;
    private String vRank;
    private Long directReferrals;
    private LocalDateTime joinedAt;
}
