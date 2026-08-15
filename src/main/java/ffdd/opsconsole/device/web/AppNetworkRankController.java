package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.AppNetworkRankService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/network/rank")
@RequiredArgsConstructor
public class AppNetworkRankController {
    private final AppNetworkRankService service;

    @GetMapping
    public ApiResult<Map<String, Object>> snapshot(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.snapshot(userId);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try { long id = Long.parseLong(String.valueOf(authentication.getPrincipal())); return id > 0 ? id : null; }
        catch (NumberFormatException ignored) { return null; }
    }
}
