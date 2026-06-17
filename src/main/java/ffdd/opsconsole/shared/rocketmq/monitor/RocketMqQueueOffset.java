package ffdd.opsconsole.shared.rocketmq.monitor;

import lombok.Data;

@Data
public class RocketMqQueueOffset {
    private String topic;
    private String brokerName;
    private Integer queueId;
    private Long brokerOffset;
    private Long consumerOffset;
    private Long pullOffset;
    private Long lag;
    private Long lastTimestamp;
}
