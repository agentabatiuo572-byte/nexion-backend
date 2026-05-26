package ffdd.common.audit;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AuditLogRecord {
    private Long id;
    private String traceId;
    private String serviceName;
    private String action;
    private String resourceType;
    private String resourceId;
    private String bizNo;
    private Long userId;
    private Long actorId;
    private String actorType;
    private String actorUsername;
    private String clientIp;
    private String method;
    private String path;
    private String result;
    private String riskLevel;
    private String detailJson;
    private LocalDateTime createdAt;
}
