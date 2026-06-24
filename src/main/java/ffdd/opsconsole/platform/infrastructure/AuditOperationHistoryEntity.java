package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_audit_operation_history")
public class AuditOperationHistoryEntity extends BaseEntity {
    private String operationId;
    private String action;
    private String status;
    private String chainText;
    private String timeLabel;
    private String note;
}
