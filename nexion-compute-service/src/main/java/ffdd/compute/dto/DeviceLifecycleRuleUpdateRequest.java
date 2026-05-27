package ffdd.compute.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class DeviceLifecycleRuleUpdateRequest {
    @Size(max = 32)
    private String scopeType;

    @Size(max = 64)
    private String scopeValue;

    @Min(0)
    private Integer startMonth;

    @Min(0)
    private Integer endMonth;

    @DecimalMin("0")
    @DecimalMax("1")
    private BigDecimal monthlyDecayRate;

    @DecimalMin("0")
    @DecimalMax("1")
    private BigDecimal floorEfficiency;

    @Min(0)
    @Max(1)
    private Integer exempt;

    @Min(0)
    @Max(1)
    private Integer status;

    private Integer sortOrder;
}
