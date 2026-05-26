package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_streak_milestone")
public class UserStreakMilestone extends BaseEntity {
    private Long userId;
    private Long milestoneId;
    private Integer milestoneDay;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String claimStatus;
    private LocalDateTime claimedAt;
}
