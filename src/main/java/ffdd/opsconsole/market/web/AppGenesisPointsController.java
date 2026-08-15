package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.AppGenesisPointsService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/genesis/points")
@RequiredArgsConstructor
public class AppGenesisPointsController {
    private final AppGenesisPointsService service;

    @GetMapping
    public ApiResult<Map<String, Object>> projection(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.projection(userId);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
