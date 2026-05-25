package ffdd.earnings.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EarningMilestoneResponse {
    private String milestoneId;
    private BigDecimal thresholdUsdt;
    private BigDecimal rewardNex;
    private String label;
    private boolean achieved;
    private BigDecimal remainingUsdt;
}
