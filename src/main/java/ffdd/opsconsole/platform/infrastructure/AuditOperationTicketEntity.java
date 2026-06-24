package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_audit_operation_ticket")
public class AuditOperationTicketEntity extends BaseEntity {
    private String operationId;
    private String action;
    private String objectText;
    private String beforeValue;
    private String afterValue;
    private String operatorName;
    private String operatorRole;
    private String operationType;
    private Integer amplifies;
    private Integer sos;
    private String timeLabel;
    private Integer mine;
    private String roleGate;
    private String reason;
    private String status;
    private String decisionReason;
    private LocalDateTime decidedAt;
}
