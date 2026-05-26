package ffdd.mission.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreakMilestoneItemResponse {
    private Long milestoneId;
    private int milestoneDay;
    private String milestoneName;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String rewardName;
    private String badgeAchievementCode;
    private int currentStreak;
    private int daysRemaining;
    private String status;
    private LocalDateTime claimedAt;
}
