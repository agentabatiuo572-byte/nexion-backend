package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_streak_milestone")
public class StreakMilestone extends BaseEntity {
    private Integer milestoneDay;
    private String milestoneName;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String rewardName;
    private String badgeAchievementCode;
    private Integer sortOrder;
    private Integer status;
}
