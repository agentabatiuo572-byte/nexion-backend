package ffdd.auth.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_permission")
public class AdminPermission extends BaseEntity {
    private String permissionCode;
    private String permissionName;
    private String resourceType;
    private String resourcePath;
    private String remark;
    private Integer status;
}
