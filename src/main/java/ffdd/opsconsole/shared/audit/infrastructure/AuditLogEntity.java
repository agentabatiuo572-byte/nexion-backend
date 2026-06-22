package ffdd.opsconsole.shared.audit.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_audit_log")
public class AuditLogEntity extends BaseEntity {
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
}
