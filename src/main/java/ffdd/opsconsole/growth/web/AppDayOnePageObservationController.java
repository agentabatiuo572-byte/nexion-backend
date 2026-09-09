package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.H3DayOnePageObservationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated boundary for the fixed Day One read-only page observations. */
@RestController
@RequiredArgsConstructor
public class AppDayOnePageObservationController {
    private final H3DayOnePageObservationService service;

    @PostMapping("/api/growth/day-one/page-observations/{surface}")
    public ApiResult<Map<String, Object>> observe(@PathVariable String surface, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.observe(userId, surface);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
