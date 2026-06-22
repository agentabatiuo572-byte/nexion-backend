package ffdd.opsconsole.shared.rocketmq.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.outbox.rocketmq")
public class RocketMqBrokerMonitorProperties {
    private boolean enabled = false;
    private String nameServer = "127.0.0.1:9876";
}
