package ffdd.opsconsole.shared.outbox.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_event_consumer_delivery")
public class EventConsumerDeliveryEntity extends BaseEntity {
    private String eventId;
    private String consumerGroup;
    private String topic;
    private String msgId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String status;
    private Integer attemptCount;
    private Integer rocketmqReconsumeTimes;
    private LocalDateTime nextRetryAt;
    private LocalDateTime processedAt;
    private LocalDateTime deadAt;
    private Integer createdCommissions;
    private String lastError;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
