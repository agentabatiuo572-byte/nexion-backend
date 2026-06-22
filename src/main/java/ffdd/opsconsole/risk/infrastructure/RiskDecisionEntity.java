package ffdd.opsconsole.risk.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_risk_decision")
public class RiskDecisionEntity extends BaseEntity {
    private String decisionNo;
    private Long userId;
    private String bizType;
    private String bizNo;
    private String region;
    private String userLevel;
    private String clientIp;
    private String deviceFingerprint;
    private String decision;
    private String reason;
    private Integer riskScore;
    private String ruleCodes;
    private String ruleSnapshot;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
}
