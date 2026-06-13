package ffdd.compute.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_device_runtime")
public class UserDeviceRuntime extends BaseEntity {
    private Long userDeviceId;
    private String onlineStatus;
    private String region;
    private String country;
    private String city;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal gpuUsage;
    private BigDecimal gpuTempC;
    private BigDecimal gpuPowerW;
    private BigDecimal vramUsedGb;
    private Integer batteryLevel;
    private Integer isCharging;
    private Integer networkReachable;
    private String thermalState;
    private String pausedReason;
    private String activeTaskNo;
    private String clientName;
    private LocalDateTime heartbeatAt;
    private String agentVersion;
}
