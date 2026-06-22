package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_trust_section")
public class TrustSectionEntity extends BaseEntity {
    private String sectionKey;
    private String description;
    private String structText;
    private String versionLabel;
    private String status;
    private String roleGate;
    private Boolean highSensitivity;
    private String lastChange;
    private Integer sortOrder;
    private String lastOperator;
}
