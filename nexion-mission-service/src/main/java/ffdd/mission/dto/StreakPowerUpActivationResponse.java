package ffdd.mission.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreakPowerUpActivationResponse {
    private Long userId;
    private String powerUpCode;
    private boolean activated;
    private String status;
    private int currentStreak;
    private int unlockStreakDays;
    private int daysRemaining;
    private String targetPath;
    private String badgeAchievementCode;
    private LocalDateTime activatedAt;
    private LocalDateTime expiresAt;
}
