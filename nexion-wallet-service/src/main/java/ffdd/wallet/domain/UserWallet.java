package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_wallet")
public class UserWallet extends BaseEntity {
    private Long userId;
    private BigDecimal usdtAvailable;
    private BigDecimal nexAvailable;
    private BigDecimal pendingWithdraw;
    private BigDecimal lifetimeEarned;
}

