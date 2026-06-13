package ffdd.earnings.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class EarningMilestoneRuleRequest {
    @NotBlank
    @Size(max = 64)
    private String milestoneId;

    @NotBlank
    @Size(max = 128)
    private String label;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal thresholdUsdt;

    @NotNull
    @DecimalMin("0.000000")
    private BigDecimal rewardNex;

    private Integer sortOrder;

    private Integer status;
}
