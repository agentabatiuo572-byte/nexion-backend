package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_staking_position")
public class StakingPosition extends BaseEntity {
    private Long userId;
    private String positionNo;
    private Long productId;
    private String productCode;
    private String productName;
    private BigDecimal amountUsdt;
    private BigDecimal apyBps;
    private BigDecimal earlyPenaltyBps;
    private Integer termDays;
    private LocalDateTime lockedAt;
    private LocalDateTime unlockAt;
    private BigDecimal estimatedInterestUsdt;
    private String status;
    private LocalDateTime claimedAt;
    private LocalDateTime earlyWithdrawnAt;
}
