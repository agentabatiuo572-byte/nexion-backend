package ffdd.mission.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class MonthlyChallengeRequest {
    @NotBlank
    @Size(max = 64)
    private String challengeCode;

    @NotBlank
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
