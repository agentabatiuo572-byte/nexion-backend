package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_exchange_order")
public class ExchangeOrder extends BaseEntity {
    private Long userId;
    private String exchangeNo;
    private String fromAsset;
    private String toAsset;
    private BigDecimal fromAmount;
    private BigDecimal toAmount;
    private BigDecimal rate;
    private String status;
}
