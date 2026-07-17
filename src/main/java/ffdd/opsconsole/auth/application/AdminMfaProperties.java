package ffdd.opsconsole.auth.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.admin-auth.mfa")
public class AdminMfaProperties {
    private String encryptionKey = System.getenv("NEXION_ADMIN_MFA_ENCRYPTION_KEY");
    private String issuer = "Nexion Ops";
    private long challengeTtlSeconds = 300;
    private int challengeMaxAttempts = 5;
    /**
     * Temporary recovery switch. It is disabled by default and applies only to
     * records whose server-side super-admin flag is active.
     */
    private boolean temporarySuperadminBypassEnabled = false;
}
