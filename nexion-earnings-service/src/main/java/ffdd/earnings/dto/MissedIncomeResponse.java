package ffdd.earnings.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MissedIncomeResponse {
    private Long userId;
    private LocalDateTime joinedAt;
    private LocalDateTime calculatedAt;
    private BigDecimal phoneDailyUsdt;
    private BigDecimal s1DailyUsdt;
    private BigDecimal dailyGapUsdt;
    private BigDecimal dayProgress;
    private BigDecimal missedTodayUsdt;
    private long daysSinceJoin;
    private BigDecimal cumulativeMissedUsdt;
}
