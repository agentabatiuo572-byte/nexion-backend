package ffdd.earnings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EarningTrendResponse {
    private Long userId;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<EarningTrendPoint> points;
    private BigDecimal totalUsdt;
    private BigDecimal totalNex;
    private BigDecimal averageDailyUsdt;
    private LocalDate bestDay;
    private BigDecimal bestDayUsdt;
}
