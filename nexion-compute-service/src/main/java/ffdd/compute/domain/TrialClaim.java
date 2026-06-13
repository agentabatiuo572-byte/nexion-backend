package ffdd.compute.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_trial_claim")
public class TrialClaim extends BaseEntity {
    private Long userId;
    private String claimNo;
    private String clientRequestNo;
    private String status;
    private Long userDeviceId;
    private String deviceName;
    private Integer durationDays;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private Integer seatsLeftToday;
    private BigDecimal offsetCapUsdt;
    private BigDecimal priceUsdt;
    private LocalDateTime claimedAt;
    private LocalDateTime expiresAt;
    private String quotaSnapshot;
}
