package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_menu")
public class AdminMenuEntity extends BaseEntity {
    private String menuCode;
    private String menuName;
    private String menuNameZh;
    private String menuNameEn;
    private Long parentId;
    private String routePath;
    private String icon;
    private Integer sortOrder;
    private String remark;
    private Integer status;
}
