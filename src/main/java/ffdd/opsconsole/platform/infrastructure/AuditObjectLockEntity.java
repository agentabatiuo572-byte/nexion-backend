package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_audit_object_lock")
public class AuditObjectLockEntity extends BaseEntity {
    private String ticketId;
    private String targetDomain;
    private String targetType;
    private String targetId;
    private String operator;
}
