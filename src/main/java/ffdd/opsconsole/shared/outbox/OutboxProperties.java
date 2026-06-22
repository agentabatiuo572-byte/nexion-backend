package ffdd.opsconsole.shared.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.outbox")
public class OutboxProperties {
    private int maxRetries = 5;

    public int maxRetries() {
        return Math.max(1, maxRetries);
    }
}
