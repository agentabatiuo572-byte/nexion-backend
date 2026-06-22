package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_disclosure_gate_action")
public class DisclosureGateActionEntity extends BaseEntity {
    private String actionKey;
    private String actionName;
    private String description;
    private String statusLabel;
    private String tone;
    private Boolean active;
    private Integer sortOrder;
    private String lastOperator;
}
