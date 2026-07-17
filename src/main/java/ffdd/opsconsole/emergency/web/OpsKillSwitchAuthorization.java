package ffdd.opsconsole.emergency.web;

import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import java.util.Locale;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("opsKillSwitchAuthorization")
public class OpsKillSwitchAuthorization {
    public boolean canToggle(KillSwitchToggleRequest request) {
        if (request == null || request.enabled() == null) {
            return false;
        }
        String direction = request.enabled().trim().toLowerCase(Locale.ROOT);
        if ("enabled".equals(direction) || "enable".equals(direction)
                || "on".equals(direction) || "true".equals(direction) || "1".equals(direction)) {
            return hasAuthority("emergency_j1_gate_resume");
        }
        if ("disabled".equals(direction) || "disable".equals(direction)
                || "off".equals(direction) || "false".equals(direction) || "0".equals(direction)) {
            return hasAuthority("emergency_j1_gate_kill");
        }
        return false;
    }

    private boolean hasAuthority(String required) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(required::equals);
    }
}
