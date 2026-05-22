package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_wallet_ledger")
public class WalletLedger extends BaseEntity {
    private Long userId;
    private String bizNo;
    private String bizType;
    private String asset;
    private String direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String status;
    private String remark;
}
