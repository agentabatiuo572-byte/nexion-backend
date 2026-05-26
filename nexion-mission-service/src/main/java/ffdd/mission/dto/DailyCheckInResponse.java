package ffdd.mission.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyCheckInResponse {
    private Long userId;
    private LocalDate checkInDate;
    private boolean completed;
    private int awardedPoints;
    private int basePoints;
    private BigDecimal rewardMultiplier;
    private int bonusPoints;
    private int streakBonusPoints;
    private int totalPoints;
    private String status;
    private int currentStreak;
    private int longestStreak;
    private List<AchievementItemResponse> unlockedAchievements;
}
