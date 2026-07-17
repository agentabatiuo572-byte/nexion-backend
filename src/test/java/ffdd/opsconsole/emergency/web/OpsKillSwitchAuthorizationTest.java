package ffdd.opsconsole.emergency.web;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsKillSwitchAuthorizationTest {
    private final OpsKillSwitchAuthorization authorization = new OpsKillSwitchAuthorization();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void killAuthorityCanDisableButCannotResume() {
        authenticate("emergency_j1_gate_kill");

        assertThat(authorization.canToggle(request("disabled"))).isTrue();
        assertThat(authorization.canToggle(request("enabled"))).isFalse();
    }

    @Test
    void resumeAuthorityCanResumeButCannotDisable() {
        authenticate("emergency_j1_gate_resume");

        assertThat(authorization.canToggle(request("enabled"))).isTrue();
        assertThat(authorization.canToggle(request("disabled"))).isFalse();
    }

    @Test
    void malformedDirectionIsAlwaysDenied() {
        authenticate("emergency_j1_gate_kill", "emergency_j1_gate_resume");

        assertThat(authorization.canToggle(request("maybe"))).isFalse();
        assertThat(authorization.canToggle(null)).isFalse();
    }

    private KillSwitchToggleRequest request(String enabled) {
        return new KillSwitchToggleRequest(enabled, "incident response", "operator");
    }

    private void authenticate(String... authorities) {
        var granted = List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", "n/a", granted));
    }
}
