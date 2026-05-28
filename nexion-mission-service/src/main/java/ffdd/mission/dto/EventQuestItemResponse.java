package ffdd.mission.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventQuestItemResponse {
    private Long questId;
    private String questCode;
    private String questName;
    private String description;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
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
