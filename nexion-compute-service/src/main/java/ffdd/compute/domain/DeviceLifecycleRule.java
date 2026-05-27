package ffdd.compute.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_device_lifecycle_rule")
public class DeviceLifecycleRule extends BaseEntity {
    private String scopeType;
    private String scopeValue;
    private Integer startMonth;
    private Integer endMonth;
    private BigDecimal monthlyDecayRate;
    private BigDecimal floorEfficiency;
    private Integer exempt;
    private Integer status;
    private Integer sortOrder;
}
