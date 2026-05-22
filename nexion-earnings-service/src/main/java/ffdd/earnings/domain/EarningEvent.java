package ffdd.earnings.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_earning_event")
public class EarningEvent extends BaseEntity {
    private String eventNo;
    private Long userId;
    private Long userDeviceId;
    private String receiptNo;
    private String asset;
    private BigDecimal amount;
    private String status;
    private LocalDateTime walletPostedAt;
}
