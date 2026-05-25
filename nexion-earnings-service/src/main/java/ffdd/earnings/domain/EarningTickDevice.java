package ffdd.earnings.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_device")
public class EarningTickDevice extends BaseEntity {
    private Long userId;
    private String status;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
}
