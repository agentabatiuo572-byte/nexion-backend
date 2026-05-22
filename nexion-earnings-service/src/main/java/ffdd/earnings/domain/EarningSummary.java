package ffdd.earnings.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_earning_summary")
public class EarningSummary extends BaseEntity {
    private Long userId;
    private LocalDate summaryDate;
    private BigDecimal usdtAmount;
    private BigDecimal nexAmount;
}
