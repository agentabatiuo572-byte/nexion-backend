package ffdd.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_webhook_subscription")
public class WebhookSubscription extends BaseEntity {
    private Long appId;
    private String eventType;
    private String callbackUrl;
    private String secret;
    private String status;
}
