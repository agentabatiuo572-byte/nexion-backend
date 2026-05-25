package ffdd.compliance.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_risk_blacklist")
public class RiskBlacklist extends BaseEntity {
    private Long userId;
    private String reason;
    private String status;
    private String source;
    private String riskLevel;
    private LocalDateTime expiresAt;
    private String createdBy;
    private String releasedBy;
    private String releaseReason;
    private LocalDateTime releasedAt;
}
