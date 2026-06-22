package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_experiment_variant")
public class CopyExperimentVariantEntity extends BaseEntity {
    private String experimentId;
    private String variantName;
    private Integer splitPct;
    private BigDecimal cvrPct;
    private Integer sortOrder;
}
