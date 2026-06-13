package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_order")
public class CommerceOrder extends BaseEntity {
    private Long userId;
    private String orderNo;
    private Long productId;
    private Integer quantity;
    private String orderType;
    private Integer itemCount;
    private BigDecimal subtotalUsdt;
    private BigDecimal discountUsdt;
    private BigDecimal amountUsdt;
    private String paymentNo;
    private String paymentStatus;
    private String orderStatus;
    private String activationStatus;
    private LocalDateTime paidAt;

    @TableField(exist = false)
    private List<CommerceOrderItem> items;
}
