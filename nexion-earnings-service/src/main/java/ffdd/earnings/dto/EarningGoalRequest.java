package ffdd.earnings.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EarningGoalRequest {
    @NotNull
    @DecimalMin("100.000000")
    private BigDecimal targetUsdt;

    @NotNull
    @Future
    private LocalDateTime deadlineAt;
}
