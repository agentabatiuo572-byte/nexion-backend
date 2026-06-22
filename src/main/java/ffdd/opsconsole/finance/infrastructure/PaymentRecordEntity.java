package ffdd.opsconsole.finance.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_payment_record")
public class PaymentRecordEntity extends BaseEntity {
    private String paymentNo;
    private String orderNo;
    private Long userId;
    private String provider;
    private String providerPaymentId;
    private BigDecimal amountUsdt;
    private String currency;
    private String paymentStatus;
    private String checkoutUrl;
    private LocalDateTime expiresAt;
    private String callbackEventId;
    private String signatureStatus;
    private String rawCallback;
    private LocalDateTime paidAt;
    private LocalDateTime failedAt;
    private String failureReason;
    private Integer reconcileAttempts;
    private LocalDateTime lastReconcileAt;
    private LocalDateTime nextReconcileAt;
    private String lastReconcileError;
    private LocalDateTime expiredAt;
}
