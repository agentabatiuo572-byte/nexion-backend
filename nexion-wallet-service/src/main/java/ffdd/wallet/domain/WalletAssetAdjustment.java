package ffdd.wallet.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_wallet_asset_adjustment")
public class WalletAssetAdjustment extends BaseEntity {
    private String adjustmentNo;
    private Long userId;
    private String asset;
    private String direction;
    private BigDecimal amount;
    private String reasonCode;
    private String reason;
    private String maker;
    private String checker;
    private String status;
    private Long ledgerId;
    private String reviewReason;
    private LocalDateTime reviewedAt;
}
