package ffdd.mission.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreakPowerUpItemResponse {
    private Long powerUpId;
    private String powerUpCode;
    private String powerUpName;
    private String i18nKey;
    private String targetPath;
    private String badgeAchievementCode;
    private int unlockStreakDays;
    private int currentStreak;
    private int daysRemaining;
    private String effectType;
    private String effectValue;
    private int durationDays;
    private String status;
    private LocalDateTime activatedAt;
    private LocalDateTime expiresAt;
}
