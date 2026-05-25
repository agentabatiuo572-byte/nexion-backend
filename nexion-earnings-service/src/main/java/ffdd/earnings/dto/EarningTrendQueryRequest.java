package ffdd.earnings.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.Data;

@Data
public class EarningTrendQueryRequest {
    @NotNull
    private Long userId;

    private LocalDate startDate;
    private LocalDate endDate;
}
