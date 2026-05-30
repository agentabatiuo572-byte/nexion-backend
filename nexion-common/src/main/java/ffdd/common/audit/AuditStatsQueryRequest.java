package ffdd.common.audit;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

@Data
public class AuditStatsQueryRequest {
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startAt;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
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
