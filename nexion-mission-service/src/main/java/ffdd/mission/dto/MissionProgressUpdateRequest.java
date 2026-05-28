package ffdd.mission.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MissionProgressUpdateRequest {
    @NotNull
    @Min(0)
    private Integer progressValue;
}
