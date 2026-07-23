package ffdd.opsconsole.finance.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_withdrawal_order")
public class WithdrawalOrderEntity extends BaseEntity {
    private Long userId;
    private String withdrawalNo;
    private String asset;
    private String chain;
    private BigDecimal amount;
    private BigDecimal fee;
    private BigDecimal d2PenaltyFeeRate;
    private BigDecimal d2GrossFee;
    private BigDecimal d2NexBurned;
    private BigDecimal d2NexFeeOffsetRate;
    private BigDecimal d2FeeWaived;
    private BigDecimal d2ActualFee;
    private BigDecimal d2NetReceive;
    private String targetAddress;
    private Long riskDecisionId;
    private String chainTxHash;
    private String status;
    private LocalDateTime chainSubmittedAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private String failureReason;
    private Integer chainBroadcastAttempts;
    private LocalDateTime nextBroadcastAt;
    private String lastBroadcastError;
    private LocalDateTime broadcastDeadAt;
}
