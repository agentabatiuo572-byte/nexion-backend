package ffdd.earnings.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class EarningMilestoneRuleUpdateRequest {
    @Size(max = 128)
    private String label;

    @DecimalMin("0.000001")
    private BigDecimal thresholdUsdt;

    @DecimalMin("0.000000")
    private BigDecimal rewardNex;

    private Integer sortOrder;

    private Integer status;
}
