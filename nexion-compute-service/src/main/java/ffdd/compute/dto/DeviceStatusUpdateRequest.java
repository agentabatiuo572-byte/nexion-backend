package ffdd.compute.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DeviceStatusUpdateRequest {
    @NotBlank
    @Size(max = 32)
    private String status;

    @Size(max = 64)
    private String region;

    @Size(max = 64)
    private String country;

    @Size(max = 64)
    private String city;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private BigDecimal latitude;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private BigDecimal longitude;

    @DecimalMin("0.0")
    @DecimalMax("150.0")
    private BigDecimal temperatureC;

    @DecimalMin("0.0")
    @DecimalMax("10000.0")
    private BigDecimal powerW;

    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private BigDecimal gpuUsage;

    @DecimalMin("0.0")
    @DecimalMax("2048.0")
    private BigDecimal vramUsedGb;

    @DecimalMin("0")
    @DecimalMax("100")
    private Integer batteryLevel;

    private Boolean isCharging;

    private Boolean networkReachable;

    @Size(max = 32)
    private String thermalState;

    @Size(max = 64)
    private String pausedReason;

    @Size(max = 96)
    private String activeTaskNo;

    @Size(max = 96)
    private String clientName;

    @Size(max = 64)
    private String agentVersion;

    private LocalDateTime reportedAt;
}
