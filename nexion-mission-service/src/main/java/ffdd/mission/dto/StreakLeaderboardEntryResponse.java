package ffdd.mission.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreakLeaderboardEntryResponse {
    private int rank;
    private Long userId;
    private String displayName;
    private String avatarUrl;
    private String countryCode;
    private int currentStreak;
    private int longestStreak;
    private LocalDate lastCheckInDate;
    private boolean checkedInToday;
}
