package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_streak_power_up")
public class UserStreakPowerUp extends BaseEntity {
    private Long userId;
    private Long powerUpId;
    private String powerUpCode;
    private String powerUpStatus;
    private LocalDateTime unlockedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime expiresAt;
}
