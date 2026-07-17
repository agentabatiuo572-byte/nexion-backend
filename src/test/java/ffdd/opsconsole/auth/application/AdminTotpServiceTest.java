package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdminTotpServiceTest {
    private final AdminTotpService service = new AdminTotpService();

    @Test
    void verifiesRfc6238Sha1VectorUsingSixDigits() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertThat(service.verify(secret, "287082", Instant.ofEpochSecond(59))).isTrue();
        assertThat(service.matchingCounter(secret, "287082", Instant.ofEpochSecond(59))).isEqualTo(1L);
        assertThat(service.verify(secret, "287083", Instant.ofEpochSecond(59))).isFalse();
    }

    @Test
    void generatedSecretHasEnoughEntropyAndProvisioningUriNeverContainsPassword() {
        String secret = service.generateSecret();

        assertThat(secret).hasSizeGreaterThanOrEqualTo(32);
        assertThat(service.provisioningUri("Nexion Ops", "superadmin", secret))
                .startsWith("otpauth://totp/")
                .contains("secret=" + secret)
                .contains("issuer=Nexion%20Ops")
                .doesNotContain("ValidPass@123");
    }
}
