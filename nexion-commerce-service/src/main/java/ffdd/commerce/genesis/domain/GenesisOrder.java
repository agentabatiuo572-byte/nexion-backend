package ffdd.commerce.genesis.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_genesis_order")
public class GenesisOrder extends BaseEntity {
    private String orderNo;
    private String clientRequestNo;
    private Long userId;
    private String seriesCode;
    private Integer quantity;
    private BigDecimal unitPriceUsdt;
    private BigDecimal amountUsdt;
    private String paymentAsset;
    private String status;
    private Long riskDecisionId;
    private Long walletLedgerId;
    private String failureReason;
    private LocalDateTime paidAt;
    private LocalDateTime completedAt;
}
