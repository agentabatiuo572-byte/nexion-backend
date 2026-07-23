package ffdd.opsconsole.risk.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.risk.k3.address-reputation")
public class K3AddressReputationProperties {
    /** Trusted provider endpoint. Empty means third-party scoring is unavailable. */
    private String endpoint = "";
    /** Provider credential. Never returned through the admin API or logs. */
    private String apiKey = "";
    private String apiKeyHeader = "X-API-Key";
    private int connectTimeoutMs = 1000;
    private int readTimeoutMs = 1500;
}
