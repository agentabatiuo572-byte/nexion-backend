package ffdd.team.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_commission_event")
public class CommissionEvent extends BaseEntity {
    private Long userId;
    private String commissionType;
    private Long sourceUserId;
    private String sourceUserName;
    private Integer layerNo;
    private String orderNo;
    private BigDecimal orderAmountUsd;
    private BigDecimal amountUsdt;
    private BigDecimal amountNex;
    private String currency;
    private String status;
    private LocalDateTime unlockAt;
    private String remark;
}

