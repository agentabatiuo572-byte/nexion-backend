package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_experiment")
public class CopyExperimentEntity extends BaseEntity {
    private String experimentId;
    private String copyKey;
    private String audience;
    private String audienceSnapshotJson;
    private String impressionsLabel;
    private String conversionsLabel;
    private String state;
    private String note;
    private String lastOperator;
}
