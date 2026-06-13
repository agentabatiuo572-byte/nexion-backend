package ffdd.earnings.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EarningGoalResponse {
    private Long goalId;
    private Long userId;
    private BigDecimal targetUsdt;
    private LocalDateTime deadlineAt;
    private BigDecimal currentEarningsUsdt;
    private BigDecimal remainingUsdt;
    private BigDecimal progressPercent;
    private Long daysLeft;
    private boolean achieved;
    private LocalDateTime achievedAt;
    private LocalDateTime createdAt;
}
