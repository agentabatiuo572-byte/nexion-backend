package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_copy_version")
public class CopyVersionEntity extends BaseEntity {
    private String copyKey;
    private String version;
    private String status;
    private String chain;
    private String tsLabel;
    private String zhText;
    private String enText;
    private String surface;
    private String audience;
    private String trafficSplit;
    private String versionNote;
    private String lastOperator;
}
