package ffdd.mission.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MissionListResponse {
    private Long userId;
    private long totalMissions;
    private long completedMissions;
    private boolean todayCheckedIn;
    private List<MissionItemResponse> records;
}
