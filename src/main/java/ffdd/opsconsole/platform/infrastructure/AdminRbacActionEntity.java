package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_rbac_action")
public class AdminRbacActionEntity extends BaseEntity {
    private String actionId;
    private String actionName;
    private String domainGroup;
    private Integer sortOrder;
    private Integer status;
}
