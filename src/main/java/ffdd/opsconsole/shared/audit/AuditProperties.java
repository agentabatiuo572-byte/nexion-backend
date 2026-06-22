package ffdd.opsconsole.shared.audit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.audit")
public class AuditProperties {
    private boolean enabled = true;
    private boolean failFast = false;
}
