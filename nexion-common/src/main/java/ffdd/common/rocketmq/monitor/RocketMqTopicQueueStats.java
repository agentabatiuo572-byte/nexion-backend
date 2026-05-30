package ffdd.common.rocketmq.monitor;

import lombok.Data;

@Data
public class RocketMqTopicQueueStats {
    private String topic;
    private String brokerName;
    private Integer queueId;
    private Long minOffset;
    private Long maxOffset;
    private Long messages;
    private Long lastUpdateTimestamp;
}
