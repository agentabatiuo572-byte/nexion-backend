package ffdd.mission.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MissionConsumeResult {
    private boolean completed;
    private int points;
    private String reason;
}
