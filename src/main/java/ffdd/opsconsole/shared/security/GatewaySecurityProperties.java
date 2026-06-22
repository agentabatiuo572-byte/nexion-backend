package ffdd.opsconsole.shared.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.gateway")
public class GatewaySecurityProperties {
    private String internalSecret = "nexion-local-gateway-secret";
}
