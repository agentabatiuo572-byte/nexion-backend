package ffdd.mission.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MissionItemResponse {
    private Long missionId;
    private String missionCode;
    private String missionName;
    private String missionType;
    private int rewardPoints;
    private boolean completed;
    private String missionStatus;
    private LocalDateTime completedAt;
}
