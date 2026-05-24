package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_withdrawal_order")
public class WithdrawalOrder extends BaseEntity {
    private Long userId;
    private String withdrawalNo;
    private String asset;
    private BigDecimal amount;
    private BigDecimal fee;
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
