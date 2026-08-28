package ffdd.opsconsole.finance.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** The real tokenized payment-method row; never carries PAN or CVV. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_wallet_bank_card")
public class WalletBankCardEntity extends BaseEntity {
    private Long userId;
    private String cardToken;
    private String cardholderName;
    private String brand;
    private String last4;
    private String expiryLabel;
    private String countryCode;
    private String status;
    private Boolean isDefault;
    private String sourceEnvironment;
    private String runId;
    private String pspRevokeStatus;
    private String unboundReason;
    private String unboundBy;
    private LocalDateTime unboundAt;
    private LocalDateTime lastRebindNotifiedAt;
    @Version
    private Long version;
}
