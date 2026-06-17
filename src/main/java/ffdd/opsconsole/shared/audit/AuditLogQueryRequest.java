package ffdd.opsconsole.shared.audit;

import lombok.Data;

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
    private Integer limit;
}
