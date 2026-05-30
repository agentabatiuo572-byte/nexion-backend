package ffdd.common.rocketmq.monitor;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class RocketMqConsumerConnection {
    private Integer connectionCount;
    private String consumeType;
    private String messageModel;
    private String consumeFromWhere;
    private List<RocketMqConsumerClient> clients = new ArrayList<>();
    private List<RocketMqSubscription> subscriptions = new ArrayList<>();
}
