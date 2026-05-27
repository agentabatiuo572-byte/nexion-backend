package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_tradein_rule")
public class TradeinRule extends BaseEntity {
    private String sourceProductNo;
    private String sourceTier;
    private String targetTier;
    private BigDecimal discountUsdt;
    private BigDecimal salvageRate;
    private Integer minHoldingMonths;
    private Integer status;
    private Integer sortOrder;
}
