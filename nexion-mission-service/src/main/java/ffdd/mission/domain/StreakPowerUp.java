package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_streak_power_up")
public class StreakPowerUp extends BaseEntity {
    private String powerUpCode;
    private String powerUpName;
    private String i18nKey;
    private String targetPath;
    private String badgeAchievementCode;
    private Integer unlockStreakDays;
    private String effectType;
    private String effectValue;
    private Integer durationDays;
    private Integer sortOrder;
    private Integer status;
}
