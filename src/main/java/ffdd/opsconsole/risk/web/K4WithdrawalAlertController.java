package ffdd.opsconsole.risk.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.risk.application.K4WithdrawalAlertService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/risk/scoring/withdrawal-alerts")
@RequiredArgsConstructor
public class K4WithdrawalAlertController {
    private final K4WithdrawalAlertService service;

    @GetMapping
    @PreAuthorize("hasAuthority('risk_k4_user_override') or @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> alerts(Authentication authentication) {
        return service.alerts(adminId(authentication));
    }

    @PostMapping("/{eventId}/read")
    @PreAuthorize("hasAuthority('risk_k4_user_override') or @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> markRead(
            Authentication authentication, @PathVariable String eventId) {
        return service.markRead(adminId(authentication), eventId);
    }

    private Long adminId(Authentication authentication) {
        try {
            long id = Long.parseLong(String.valueOf(authentication == null ? null : authentication.getPrincipal()));
            if (id > 0) return id;
        } catch (RuntimeException ignored) {
            // Fail closed below.
        }
        throw new IllegalArgumentException("ADMIN_ID_REQUIRED");
    }
}
