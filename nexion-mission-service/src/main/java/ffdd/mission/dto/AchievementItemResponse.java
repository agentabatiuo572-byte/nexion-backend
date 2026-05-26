package ffdd.mission.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AchievementItemResponse {
    private Long achievementId;
    private String achievementCode;
    private String achievementName;
    private String category;
    private String triggerType;
    private int triggerValue;
    private int rewardPoints;
    private String status;
    private int progress;
    private LocalDateTime unlockedAt;
    private LocalDateTime claimedAt;
}
