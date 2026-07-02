package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_rbac_grant")
public class AdminRbacGrantEntity extends BaseEntity {
    private String actionId;
    private String roleKey;
    private String grantValue;
    private Integer status;
}
