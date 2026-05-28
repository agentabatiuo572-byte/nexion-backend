package ffdd.mission.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class MonthlyChallengeUpdateRequest {
    @Size(max = 128)
    private String challengeName;
    @Size(max = 512)
    private String description;
    @Size(max = 64)
    private String theme;
    @Min(0)
    private Integer monthsFrom;
    @Min(0)
    private Integer monthsTo;
    @Size(max = 64)
    private String targetType;
    @Min(1)
    private Integer targetValue;
    @Size(max = 32)
    private String rewardType;
    @DecimalMin("0.000001")
    private BigDecimal rewardAmount;
    @Size(max = 128)
    private String rewardName;
    @Size(max = 64)
    private String badgeAchievementCode;
    private Integer sortOrder;
    private Integer status;
}
