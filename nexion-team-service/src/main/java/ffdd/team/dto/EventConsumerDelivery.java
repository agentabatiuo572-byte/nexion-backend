package ffdd.team.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EventConsumerDelivery {
    private Long id;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
