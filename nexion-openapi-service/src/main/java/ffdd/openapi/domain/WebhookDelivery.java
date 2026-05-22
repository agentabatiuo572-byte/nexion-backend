package ffdd.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_webhook_delivery")
public class WebhookDelivery extends BaseEntity {
    private Long subscriptionId;
    private Long appId;
    private String eventType;
    private String payload;
    private String status;
    private Integer retryCount;
    private String lastError;
}
