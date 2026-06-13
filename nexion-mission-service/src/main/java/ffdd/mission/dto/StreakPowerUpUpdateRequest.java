package ffdd.mission.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StreakPowerUpUpdateRequest {
    @Size(max = 128)
    private String powerUpName;

    @Size(max = 128)
    private String i18nKey;

    @Size(max = 256)
    private String targetPath;

    @Size(max = 64)
    private String badgeAchievementCode;

    @Min(1)
    private Integer unlockStreakDays;

    @Size(max = 64)
    private String effectType;

    @Size(max = 128)
    private String effectValue;

    @Min(0)
    private Integer durationDays;

    private Integer sortOrder;

    private Integer status;
}
