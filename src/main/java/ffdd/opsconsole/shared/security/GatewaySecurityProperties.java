package ffdd.opsconsole.shared.security;

import java.util.ArrayList;
import java.util.List;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.gateway")
public class GatewaySecurityProperties {
    private boolean headerAuthenticationEnabled = false;
    private String internalSecret = "";
    private List<String> trustedProxyAddresses = new ArrayList<>(List.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1"));

    @PostConstruct
    void validateConfiguration() {
        if (headerAuthenticationEnabled && (internalSecret == null || internalSecret.trim().length() < 32)) {
            throw new IllegalStateException(
                    "NEXION_GATEWAY_INTERNAL_SECRET must contain at least 32 characters when gateway header authentication is enabled");
        }
    }

    public boolean isTrustedProxy(String remoteAddress) {
        if (remoteAddress == null) return false;
        String candidate = remoteAddress.trim();
        return trustedProxyAddresses.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .anyMatch(candidate::equalsIgnoreCase);
    }
}
