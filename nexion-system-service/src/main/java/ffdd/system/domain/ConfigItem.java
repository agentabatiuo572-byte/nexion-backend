package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_config_item")
public class ConfigItem extends BaseEntity {
    private String configKey;
    private String configValue;
    private String valueType;
    private String configGroup;
    private String visibility;
    private String remark;
    private Integer status;
}
