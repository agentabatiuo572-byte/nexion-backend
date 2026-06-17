package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_config_item")
public class PlatformConfigItemEntity extends BaseEntity {
    private String configKey;
    private String configValue;
    private String valueType;
    private String configGroup;
    private String visibility;
    private String remark;
    private Integer status;
}
