package ffdd.opsconsole.auth.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_role_permission")
public class AdminRolePermissionEntity extends BaseEntity {
    private Long roleId;
    private Long permissionId;
}
