package ffdd.device.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_device")
public class ComputeDevice extends BaseEntity {
    private Long userId;
    private Long deviceId;
    private String instanceNo;
    private String name;
    private String status;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private LocalDateTime lastSeenAt;
    private LocalDateTime activatedAt;
}
