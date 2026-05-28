package ffdd.mission.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampaignClaimResponse {
    private Long userId;
    private String campaignType;
    private String campaignCode;
    private boolean claimed;
    private String status;
    private int progressValue;
    private int targetValue;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String rewardName;
    private int awardedPoints;
    private int totalPoints;
    private LocalDateTime claimedAt;
}
