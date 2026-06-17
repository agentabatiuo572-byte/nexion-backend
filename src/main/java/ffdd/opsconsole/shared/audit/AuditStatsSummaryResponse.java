package ffdd.opsconsole.shared.audit;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class AuditStatsSummaryResponse {
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Long total;
    private List<AuditStatsBucket> byResult;
    private List<AuditStatsBucket> byRiskLevel;
}
