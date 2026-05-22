package ffdd.auth.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_role_menu")
public class AdminRoleMenu extends BaseEntity {
    private Long roleId;
    private Long menuId;
}
