package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_event_quest")
public class EventQuest extends BaseEntity {
    private String questCode;
    private String questName;
    private String description;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String targetType;
    private Integer targetValue;
    private String rewardType;
    private BigDecimal rewardAmount;
    private String rewardName;
    private String badgeAchievementCode;
    private Integer sortOrder;
    private Integer status;
}
