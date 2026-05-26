package ffdd.mission.service;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyCheckInReward {
    private int basePoints;
    private BigDecimal multiplier;
    private int awardedPoints;
}
