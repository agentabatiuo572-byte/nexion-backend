package ffdd.mission.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AchievementUpdateRequest {
    @Size(max = 128)
    private String achievementName;

    @Size(max = 512)
    private String description;

    @Size(max = 32)
    private String category;

    @Size(max = 64)
    private String iconKey;

    @Size(max = 32)
    private String accentColor;

    @Size(max = 32)
    private String triggerType;

    @Min(1)
    private Integer triggerValue;

    @Min(0)
    private Integer rewardPoints;

    private Integer sortOrder;

    private Integer status;
}
