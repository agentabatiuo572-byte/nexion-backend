package ffdd.opsconsole.shared.rocketmq.monitor;

import lombok.Data;

@Data
public class RocketMqConsumerClient {
    private String clientId;
    private String clientAddr;
    private String language;
    private Integer version;
}
