package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 文案位置槽位配置(独立表 nx_content_copy_position)——模块内具体展示位,与投放位置(surface 大模块)正交。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_copy_position")
public class CopyPositionEntity extends BaseEntity {
    private String positionKey;
    private String name;
    private String surface;
    private Integer sortOrder;
    private String status;
    private String lastOperator;
}
