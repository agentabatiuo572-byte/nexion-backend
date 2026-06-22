package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("nx_admin_role")
public class AdminRoleOptionEntity {
    @TableId
    private Long id;
    private String roleName;
    private Integer status;
}
