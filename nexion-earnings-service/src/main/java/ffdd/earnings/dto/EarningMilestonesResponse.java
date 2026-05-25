package ffdd.earnings.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EarningMilestonesResponse {
    private Long userId;
    private BigDecimal lifetimeUsdt;
    private List<EarningMilestoneResponse> milestones;
    private EarningMilestoneResponse nextMilestone;
    private BigDecimal progressPercent;
}
