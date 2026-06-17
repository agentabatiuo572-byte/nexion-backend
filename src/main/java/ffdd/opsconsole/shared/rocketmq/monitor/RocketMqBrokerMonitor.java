package ffdd.opsconsole.shared.rocketmq.monitor;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class RocketMqBrokerMonitor {
    private boolean enabled;
    private boolean ok;
    private String name;
    private String nameServer;
    private String acl;
    private String topic;
    private String consumerGroup;
    private String dlqTopic;
    private Long totalLag;
    private Long dlqMessages;
    private Double consumeTps;
    private List<RocketMqQueueOffset> offsets = new ArrayList<>();
    private RocketMqTopicStats dlq;
    private RocketMqConsumerConnection connection;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
