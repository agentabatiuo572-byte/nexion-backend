package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_monthly_challenge")
public class MonthlyChallenge extends BaseEntity {
    private String challengeCode;
    private String challengeName;
    private String description;
    private String theme;
    private Integer monthsFrom;
    private Integer monthsTo;
    private String targetType;
    private Integer targetValue;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String rewardName;
    private String badgeAchievementCode;
    private Integer sortOrder;
    private Integer status;
}
