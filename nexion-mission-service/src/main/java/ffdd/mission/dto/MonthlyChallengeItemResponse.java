package ffdd.mission.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyChallengeItemResponse {
    private Long challengeId;
    private String challengeCode;
    private String challengeName;
    private String description;
    private String theme;
    private Integer monthsFrom;
    private Integer monthsTo;
    private String targetType;
    private int targetValue;
    private int progressValue;
    private int progressPercent;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String rewardName;
    private String badgeAchievementCode;
    private String status;
    private LocalDateTime claimedAt;
}
