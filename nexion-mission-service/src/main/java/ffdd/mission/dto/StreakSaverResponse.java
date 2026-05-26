package ffdd.mission.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreakSaverResponse {
    private Long userId;
    private boolean restored;
    private String status;
    private int currentStreak;
    private int longestStreak;
    private int remainingStreakSavers;
    private LocalDate lastCheckInDate;
    private int recoverableStreak;
    private boolean checkedInToday;
}
