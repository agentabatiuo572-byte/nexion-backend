package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_staking_product")
public class StakingProduct extends BaseEntity {
    private String productCode;
    private String name;
    private String asset;
    private Integer termDays;
    private BigDecimal apyBps;
    private BigDecimal earlyPenaltyBps;
    private BigDecimal minAmount;
    private Integer sortOrder;
    private String status;
}
