package ffdd.opsconsole.auth.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_role_relation")
public class AdminRoleRelationEntity extends BaseEntity {
    private Long adminId;
    private Long roleId;
}
