package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_notification_cap_rule")
public class NotificationCapRuleEntity extends BaseEntity {
    private String tier;
    private String capLabel;
    private String policy;
    private Integer locked;
    private Integer sortOrder;
    private Integer status;
    private String lastOperator;
}
