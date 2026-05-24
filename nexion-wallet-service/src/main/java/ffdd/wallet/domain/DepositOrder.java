package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_deposit_order")
public class DepositOrder extends BaseEntity {
    private Long userId;
    private String depositNo;

    @TableField("chain_name")
    private String chain;

    private String chainTxHash;
    private String asset;
    private BigDecimal amount;
    private Integer confirmations;
    private String status;
    private Long ledgerId;
    private LocalDateTime confirmedAt;
    private LocalDateTime creditedAt;
    private LocalDateTime failedAt;
    private String failureReason;
}
