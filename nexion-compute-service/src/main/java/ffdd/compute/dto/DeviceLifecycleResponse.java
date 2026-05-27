package ffdd.compute.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLifecycleResponse {
    private Long userDeviceId;
    private Integer monthsOwned;
    private BigDecimal currentEfficiency;
    private BigDecimal effectiveDailyUsdt;
    private BigDecimal effectiveDailyNex;
    private BigDecimal floorEfficiency;
    private boolean exempt;
}
