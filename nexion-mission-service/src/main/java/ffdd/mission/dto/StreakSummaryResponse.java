package ffdd.mission.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreakSummaryResponse {
    private Long userId;
    private int currentStreak;
    private int longestStreak;
    private int streakSavers;
    private LocalDate lastCheckInDate;
    private int nextMilestoneDays;
    private boolean checkedInToday;
}
