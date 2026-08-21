package ffdd.opsconsole.janus.web;

import ffdd.opsconsole.janus.application.JanusSandboxEnrollmentService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/janus/sandbox")
@RequiredArgsConstructor
public class AppJanusSandboxEnrollmentController {
    private final JanusSandboxEnrollmentService enrollmentService;

    @PostMapping("/enrollment")
    public ApiResult<JanusSandboxEnrollmentService.Issue> enroll(@RequestBody EnrollmentRequest request,
                                                                 Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        try {
            return ApiResult.ok(enrollmentService.issue(userId, request == null ? null : request.deviceId()));
        } catch (IllegalArgumentException exception) {
            return ApiResult.fail(422, exception.getMessage());
        } catch (IllegalStateException exception) {
            return ApiResult.fail(503, exception.getMessage());
        }
    }

    private static Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record EnrollmentRequest(String deviceId) { }
}
