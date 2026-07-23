package ffdd.opsconsole.shared.audit;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

@Data
public class AuditLogQueryRequest {
    private String traceId;
    private String serviceName;
    private String action;
    private String resourceType;
    private String resourceId;
    private String bizNo;
    private Long userId;
    private Long actorId;
    private String result;
    private String riskLevel;
    /** Public A2 filter names shared with the frontend. */
    private String domain;
    private String operator;
    private String operatorExact;
    private String object;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
    /** Server-owned visibility constraint; request binding must never broaden it. */
    private List<String> allowedDomains;
    private Integer limit;
}
