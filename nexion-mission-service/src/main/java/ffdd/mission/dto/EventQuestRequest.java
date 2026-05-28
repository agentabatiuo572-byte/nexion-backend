package ffdd.mission.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EventQuestRequest {
    @NotBlank
    @Size(max = 64)
    private String questCode;

    @NotBlank
    @Size(max = 128)
    private String questName;

    @Size(max = 512)
    private String description;

    private LocalDateTime startsAt;

    private LocalDateTime endsAt;

    @NotBlank
    @Size(max = 64)
    private String targetType;

    @NotNull
    @Min(1)
    private Integer targetValue;

    @NotBlank
    @Size(max = 32)
    private String rewardType;

    @NotNull
    @DecimalMin("0.000001")
    private BigDecimal rewardAmount;

    @NotBlank
    @Size(max = 128)
    private String rewardName;

    @Size(max = 64)
    private String badgeAchievementCode;

    private Integer sortOrder;

    private Integer status;
}
