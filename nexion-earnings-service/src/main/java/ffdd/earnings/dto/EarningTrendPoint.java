package ffdd.earnings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EarningTrendPoint {
    private LocalDate summaryDate;
    private BigDecimal usdtAmount;
    private BigDecimal nexAmount;
    private BigDecimal cumulativeUsdt;
    private BigDecimal cumulativeNex;
}
