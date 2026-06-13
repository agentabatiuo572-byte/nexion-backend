package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_price_index")
public class PriceIndex extends BaseEntity {
    private String metricCode;
    private String metricLabel;
    private String unitLabel;
    private BigDecimal priceUsdt;
    private BigDecimal deltaPercent;
    private BigDecimal volume24hUsdt;
    private String sparkline;
    private String status;
    private LocalDateTime sampledAt;
}
