package ffdd.earnings.dto;

import java.time.LocalDate;
import lombok.Data;

@Data
public class EarningSummaryQueryRequest {
    private Long userId;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
