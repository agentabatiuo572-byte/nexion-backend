package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_nex_lock_order")
public class NexLockOrder extends BaseEntity {
    private Long userId;
    private String lockNo;
    private BigDecimal amountNex;
    private BigDecimal apyBps;
    private Integer termMonths;
    private LocalDateTime lockedAt;
    private LocalDateTime unlockAt;
    private BigDecimal estimatedRewardNex;
    private String status;
}
