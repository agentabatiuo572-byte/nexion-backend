package ffdd.mission.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class MissionUpdateRequest {
    private String missionName;
    private String missionType;

    @Min(0)
    private Integer rewardPoints;

    private Integer status;
}
