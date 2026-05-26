package ffdd.mission.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreakMilestoneClaimResponse {
    private Long userId;
    private int milestoneDay;
    private boolean claimed;
    private String status;
    private int currentStreak;
    private int daysRemaining;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String rewardName;
    private int awardedPoints;
    private int totalPoints;
    private String badgeAchievementCode;
    private LocalDateTime claimedAt;
}
