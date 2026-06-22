package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_experiment_framework")
public class CopyFrameworkParamEntity extends BaseEntity {
    private String paramKey;
    private String paramName;
    private String currentValue;
    private String description;
    private Integer sortOrder;
    private String lastOperator;
}
