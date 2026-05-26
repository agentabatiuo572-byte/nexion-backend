package ffdd.common.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogWriteRequest {
    private String traceId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String bizNo;
    private Long userId;
    private Long actorId;
    private String actorType;
    private String actorUsername;
    private String method;
    private String path;
    private String clientIp;
    private String result;
    private String riskLevel;
    private Object detail;
}
