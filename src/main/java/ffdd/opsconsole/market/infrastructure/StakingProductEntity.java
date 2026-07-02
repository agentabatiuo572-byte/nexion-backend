package ffdd.opsconsole.market.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_staking_product")
public class StakingProductEntity extends BaseEntity {
    private String productCode;
    private String name;
    private String asset;
    private Integer termDays;
    private BigDecimal apyBps;
    private BigDecimal earlyPenaltyBps;
    private BigDecimal minAmount;
    private BigDecimal rewardMultiplier;
    private Integer ticketPerOrder;
    private String presetAmounts;
    private Integer sortOrder;
    private String status;
}
