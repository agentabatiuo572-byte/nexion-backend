package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_event_quest")
public class UserEventQuest extends BaseEntity {
    private Long userId;
    private Long questId;
    private String questCode;
    private Integer progressValue;
    private String claimStatus;
    private String rewardType;
    private BigDecimal rewardAmount;
    private LocalDateTime claimedAt;
}
