package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_monthly_challenge")
public class UserMonthlyChallenge extends BaseEntity {
    private Long userId;
    private Long challengeId;
    private String challengeCode;
    private Integer progressValue;
    private String claimStatus;
    private String rewardType;
    private BigDecimal rewardAmount;
    private LocalDateTime claimedAt;
}
