package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_order_item")
public class CommerceOrderItem extends BaseEntity {
    private String orderNo;
    private Long productId;
    private String productNo;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPriceUsdt;
    private BigDecimal lineAmountUsdt;
    private Integer sortOrder;
}
