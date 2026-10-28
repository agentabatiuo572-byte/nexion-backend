package ffdd.opsconsole.finance.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nx_cregis_deposit_policy")
public class CregisDepositPolicyEntity {
    @TableId
    private Long version;
    private BigDecimal minDepositUsdt;
    private BigDecimal feeUsdt;
    private Integer confirmations;
    private String operationId;
    private String reason;
    private LocalDateTime createdAt;
}
