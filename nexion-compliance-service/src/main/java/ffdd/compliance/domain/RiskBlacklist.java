package ffdd.compliance.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_risk_blacklist")
public class RiskBlacklist extends BaseEntity {
    private Long userId;
    private String reason;
    private String status;
}
