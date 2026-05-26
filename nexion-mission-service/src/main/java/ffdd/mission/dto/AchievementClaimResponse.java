package ffdd.mission.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AchievementClaimResponse {
    private Long userId;
    private String achievementCode;
    private String status;
    private boolean claimed;
    private int awardedPoints;
    private int totalPoints;
}
