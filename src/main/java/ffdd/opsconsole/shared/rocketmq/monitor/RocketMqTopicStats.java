package ffdd.opsconsole.shared.rocketmq.monitor;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class RocketMqTopicStats {
    private boolean available;
    private String topic;
    private Long totalMessages;
    private List<RocketMqTopicQueueStats> queues = new ArrayList<>();
}
