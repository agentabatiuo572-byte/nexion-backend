package ffdd.common.audit;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

@Data
public class AuditStatsQueryRequest {
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startAt;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endAt;

    private Integer days;
    private String serviceName;
    private String action;
    private String riskLevel;
    private String result;
    private Long userId;
    private Long actorId;
    private Integer limit;
}
