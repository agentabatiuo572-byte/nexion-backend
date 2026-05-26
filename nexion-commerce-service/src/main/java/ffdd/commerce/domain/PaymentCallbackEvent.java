package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_payment_callback_event")
public class PaymentCallbackEvent extends BaseEntity {
    private String provider;
    private String providerEventId;
    private String paymentNo;
    private String orderNo;
    private String eventStatus;
    private String processingStatus;
    private String signatureStatus;
    private String rawPayload;
    private String failureReason;
}
