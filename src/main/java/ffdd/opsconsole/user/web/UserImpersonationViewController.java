package ffdd.opsconsole.user.web;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.application.OpsUserService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/impersonation")
@RequiredArgsConstructor
public class UserImpersonationViewController {
    private final OpsUserService userService;

    @GetMapping("/view")
    @PreAuthorize("hasAuthority('impersonate_readonly')")
    public ApiResult<Map<String, Object>> view(
            Authentication authentication,
            @RequestParam(defaultValue = "HOME") String page) {
        if (authentication == null || !(authentication.getDetails() instanceof Map<?, ?> details)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "IMPERSONATION_CONTEXT_INVALID");
        }
        Long userId;
        String sessionNo;
        try {
            userId = Long.valueOf(String.valueOf(authentication.getPrincipal()));
            sessionNo = String.valueOf(details.get("sessionId"));
        } catch (RuntimeException ex) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "IMPERSONATION_CONTEXT_INVALID");
        }
        return userService.impersonationReadonlyView(userId, sessionNo, page);
    }
}
