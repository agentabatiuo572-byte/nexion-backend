package ffdd.opsconsole.shared.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.outbox.rocketmq.consumer")
public class EventConsumerDeliveryProperties {
    private int maxRetries = 5;
    private int processingTimeoutSeconds = 300;

    public int maxRetries() {
        return Math.max(1, maxRetries);
    }

    public int processingTimeoutSeconds() {
        return Math.max(30, processingTimeoutSeconds);
    }
}
