package ffdd.team.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_commission_rule")
public class CommissionRule extends BaseEntity {
    private String commissionType;
    private Integer layerNo;
    private String rankCode;
    private BigDecimal usdtRate;
    private BigDecimal nexPerUsd;
    private BigDecimal fixedNex;
    private BigDecimal dailyCapUsdt;
    private Integer cooldownDays;
    private Integer status;
}

