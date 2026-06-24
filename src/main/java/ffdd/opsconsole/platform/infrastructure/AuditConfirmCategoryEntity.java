package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_audit_confirm_category")
public class AuditConfirmCategoryEntity extends BaseEntity {
    private String categoryName;
    private String examples;
    private String roleGate;
    private Integer sortOrder;
}
