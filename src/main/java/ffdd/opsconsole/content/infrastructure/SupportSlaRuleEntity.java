package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_support_sla_rule")
public class SupportSlaRuleEntity extends BaseEntity {
    private String category;
    private Integer firstResponseMins;
    private Integer resolutionHours;
    private String queue;
    private String escalation;
    private Integer status;
}
