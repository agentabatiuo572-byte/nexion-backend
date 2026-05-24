package ffdd.compliance.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_risk_decision")
public class RiskDecision extends BaseEntity {
    private String decisionNo;
    private Long userId;
    private String bizType;
    private String bizNo;
    private String decision;
    private String reason;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
}
