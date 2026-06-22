package ffdd.opsconsole.shared.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.jwt")
public class JwtProperties {
    private String secret = "nexion-development-secret-key-change-me-please";
    private long ttlMinutes = 1440;
}
