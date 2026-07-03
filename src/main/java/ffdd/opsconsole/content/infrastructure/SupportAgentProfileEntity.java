package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_support_agent_profile")
public class SupportAgentProfileEntity extends BaseEntity {
    private Long adminId;
    private String seatType;
    private String position;
    private String serviceTypes;
    private String tags;
    private Integer maxConcurrent;
    private Integer enabled;
    private Integer transferable;
    private Integer busy;
}
