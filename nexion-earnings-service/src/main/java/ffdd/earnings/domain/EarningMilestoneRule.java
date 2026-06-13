package ffdd.earnings.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_earning_milestone_rule")
public class EarningMilestoneRule extends BaseEntity {
    private String milestoneId;
    private String label;
    private BigDecimal thresholdUsdt;
    private BigDecimal rewardNex;
    private Integer sortOrder;
    private Integer status;
}
