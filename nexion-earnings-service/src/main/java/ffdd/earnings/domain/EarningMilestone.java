package ffdd.earnings.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_earning_milestone")
public class EarningMilestone extends BaseEntity {
    private Long userId;
    private String milestoneId;
    private BigDecimal thresholdUsdt;
    private BigDecimal rewardNex;
    private String status;
    private String eventNo;
    private LocalDateTime achievedAt;
}
