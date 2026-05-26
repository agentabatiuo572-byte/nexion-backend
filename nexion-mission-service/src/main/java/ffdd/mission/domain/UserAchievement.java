package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_achievement")
public class UserAchievement extends BaseEntity {
    private Long userId;
    private Long achievementId;
    private String achievementCode;
    private String achievementStatus;
    private LocalDateTime unlockedAt;
    private LocalDateTime claimedAt;
}
