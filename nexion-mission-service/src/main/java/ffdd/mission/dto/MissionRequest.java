package ffdd.mission.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MissionRequest {
    @NotBlank
    private String missionCode;

    @NotBlank
    private String missionName;

    @NotBlank
    private String missionType;

    @Min(0)
    private Integer rewardPoints;

    private Integer status;
}
