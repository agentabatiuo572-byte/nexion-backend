package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_permission")
public class AdminPermissionEntity extends BaseEntity {
    private String permissionCode;
    private String permissionName;
    private String resourceType;
    private String resourcePath;
    private Long menuId;
    private String permType;
    private Integer amplifies;
    private String remark;
    private Integer status;
}
