package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.AppBinaryProjectionService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated user-only F3 snapshot; never accepts an arbitrary user id. */
@RestController
@RequiredArgsConstructor
public class AppBinaryController {
    private final AppBinaryProjectionService service;

    @GetMapping("/api/team/binary")
    public ApiResult<Map<String, Object>> current(Authentication authentication) {
        Long userId = userId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        return ApiResult.ok(service.snapshot(userId));
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
