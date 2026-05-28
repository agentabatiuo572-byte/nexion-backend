package ffdd.mission.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EventQuestUpdateRequest {
    @Size(max = 128)
    private String questName;
    @Size(max = 512)
    private String description;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
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
