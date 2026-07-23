package ffdd.opsconsole.user.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.application.C2AdminAlertService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Role-trimmed human-visible alert endpoint for C2 state changes. */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/users/account-actions")
@RequiredArgsConstructor
public class C2AdminAlertController {
    private final C2AdminAlertService alertService;
    private final AdminOperatorRoleResolver roleResolver;

    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('user_c2_read')")
    public ApiResult<Map<String, Object>> alerts() {
        String roleCode = roleResolver.resolveCode();
        if (!"SUPER_ADMIN".equals(roleCode) && !"RISK".equals(roleCode)) {
            return ApiResult.fail(403, "C2_ALERTS_ROLE_FORBIDDEN");
        }
        return alertService.alerts();
    }
}
