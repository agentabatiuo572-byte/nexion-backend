package ffdd.compute.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_device")
public class UserDevice extends BaseEntity {
    private Long userId;
    private String sourceOrderNo;
    private Long productId;
    private String productTier;
    private String instanceNo;
    private String name;
    private String deviceType;
    private String status;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private LocalDateTime lastSeenAt;
    private LocalDateTime purchasedAt;
    private LocalDateTime activatedAt;
    private Integer pendingDeactivate;
}
