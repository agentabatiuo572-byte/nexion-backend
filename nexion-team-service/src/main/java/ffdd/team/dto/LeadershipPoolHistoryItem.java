package ffdd.team.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeadershipPoolHistoryItem {
    private Long id;
    private String orderNo;
    private BigDecimal amountUsdt;
    private String status;
    private LocalDateTime unlockAt;
    private LocalDateTime createdAt;
    private String remark;
}
