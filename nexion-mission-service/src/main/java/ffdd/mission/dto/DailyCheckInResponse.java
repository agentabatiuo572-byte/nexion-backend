package ffdd.mission.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyCheckInResponse {
    private Long userId;
    private LocalDate checkInDate;
    private boolean completed;
    private int awardedPoints;
    private int totalPoints;
    private String status;
}
