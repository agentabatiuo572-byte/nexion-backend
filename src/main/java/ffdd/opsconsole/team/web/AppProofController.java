package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.AppProofService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppProofController {
    private final AppProofService service;

    @GetMapping("/proof")
    public ApiResult<Map<String, Object>> proof(Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.snapshot(userId);
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try { long value = Long.parseLong(String.valueOf(authentication.getPrincipal())); return value > 0 ? value : null; }
        catch (NumberFormatException exception) { return null; }
    }
}
