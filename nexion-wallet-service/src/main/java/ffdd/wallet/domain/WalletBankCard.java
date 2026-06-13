package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_wallet_bank_card")
public class WalletBankCard extends BaseEntity {
    private Long userId;
    private String cardToken;
    private String cardholderName;
    private String brand;
    private String last4;
    private String countryCode;
    private String status;
    private Integer isDefault;
}
