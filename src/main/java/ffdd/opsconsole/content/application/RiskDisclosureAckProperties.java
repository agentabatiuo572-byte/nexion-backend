package ffdd.opsconsole.content.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.disclosure.ack")
public class RiskDisclosureAckProperties {
    private long tokenTtlMinutes = 10;
    private long minReadSeconds = 5;

    long boundedTokenTtlMinutes() {
        return Math.max(1, Math.min(tokenTtlMinutes, 60));
    }

    long boundedMinReadSeconds() {
        return Math.max(1, Math.min(minReadSeconds, 300));
    }
}
