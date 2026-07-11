package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_role")
public class AdminRoleEntity extends BaseEntity {
    private String roleCode;
    private String roleName;
    private String remark;
    private Integer status;
}
