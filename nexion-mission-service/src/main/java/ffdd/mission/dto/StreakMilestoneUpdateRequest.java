package ffdd.mission.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class StreakMilestoneUpdateRequest {
    @Min(1)
    private Integer milestoneDay;

    @Size(max = 128)
    private String milestoneName;

    @Size(max = 32)
    private String rewardType;

    @DecimalMin("0.000000")
    private BigDecimal rewardAmount;

    @Size(max = 128)
    private String rewardName;

    @Size(max = 64)
    private String badgeAchievementCode;

    private Integer sortOrder;

    private Integer status;
}
