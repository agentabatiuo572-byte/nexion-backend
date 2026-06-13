package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_product_waitlist")
public class ProductWaitlist extends BaseEntity {
    private Long productId;
    private String productNo;
    private String productName;
    private Long userId;
    private String unlockPhase;
    private String status;
    private LocalDateTime notifiedAt;
}
