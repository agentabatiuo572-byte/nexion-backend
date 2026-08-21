package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JanusSandboxEnrollmentServiceTest {
    @Test
    void enrollmentRotatesAndBindsCredentialToUserDeviceTargetAndExpiry() {
        AtomicLong now = new AtomicLong(1_000L);
        JanusSandboxEnrollmentService service = service("dev", "SANDBOX", now);

        var first = service.issue(42L, "device-42");
        assertThat(first.subjectId()).isEqualTo("42");
        assertThat(first.allowedTargets()).containsExactly("approved");
        assertThat(service.verify(42L, "device-42", first.token())).isTrue();
        assertThat(service.verify(43L, "device-42", first.token())).isFalse();
        assertThat(service.verify(42L, "device-43", first.token())).isFalse();
        assertThat(service.allowsTarget("approved")).isTrue();
        assertThat(service.allowsTarget("production")).isFalse();

        var rotated = service.issue(42L, "device-42");
        assertThat(rotated.token()).isNotEqualTo(first.token());
        assertThat(service.verify(42L, "device-42", first.token())).isFalse();
        assertThat(service.verify(42L, "device-42", rotated.token())).isTrue();

        now.set(rotated.expiresAt());
        assertThat(service.verify(42L, "device-42", rotated.token())).isFalse();
    }

    @Test
    void enrollmentIsUnavailableOutsideOneExplicitSandboxProfile() {
        AtomicLong now = new AtomicLong(1_000L);
        assertThatThrownBy(() -> service("prod", "SANDBOX", now).issue(42L, "device-42"))
                .hasMessage("JANUS_SANDBOX_ENROLLMENT_FORBIDDEN");
        assertThatThrownBy(() -> service("dev", "PRODUCTION", now).issue(42L, "device-42"))
                .hasMessage("JANUS_SANDBOX_ENROLLMENT_FORBIDDEN");
    }

    private JanusSandboxEnrollmentService service(String profile, String mode, AtomicLong now) {
        return new JanusSandboxEnrollmentService(new MockEnvironment() {
            @Override public String[] getActiveProfiles() { return new String[]{profile}; }
        }, mode, "approved", 60_000L, now::get, new SecureRandom());
    }
}
