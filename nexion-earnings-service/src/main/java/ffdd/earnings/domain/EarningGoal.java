package ffdd.earnings.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_earning_goal")
public class EarningGoal extends BaseEntity {
    private Long userId;
    private BigDecimal targetUsdt;
    private LocalDateTime deadlineAt;
    private Integer achieved;
    private LocalDateTime achievedAt;
    private LocalDateTime deletedAt;
}
