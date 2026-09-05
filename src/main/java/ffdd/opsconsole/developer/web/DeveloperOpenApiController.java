package ffdd.opsconsole.developer.web;

import ffdd.opsconsole.home.application.AppHomeOverviewService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** First real developer-key business surface: the key owner can read its own home facts. */
@RestController
@RequestMapping("/openapi/v1/developer")
@RequiredArgsConstructor
public class DeveloperOpenApiController {
    private final AppHomeOverviewService homeOverviewService;

    @GetMapping("/home-overview")
    @PreAuthorize("hasRole('DEVELOPER_API')")
    public ApiResult<Map<String, Object>> homeOverview(Authentication auth) {
        Long id = userId(auth);
        return id == null ? ApiResult.fail(403, "DEVELOPER_API_KEY_REQUIRED")
                : homeOverviewService.overview(id);
    }

    private Long userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"DEVELOPER_API_KEY".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(auth.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
