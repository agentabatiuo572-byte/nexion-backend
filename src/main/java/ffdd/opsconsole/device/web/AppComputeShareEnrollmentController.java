package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.AppComputeShareEnrollmentService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/compute-share/enrollments")
@RequiredArgsConstructor
public class AppComputeShareEnrollmentController {
    private final AppComputeShareEnrollmentService service;

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest request,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED")
                : service.create(userId, request == null ? null : request.requestedGpuModel(), idempotencyKey);
    }

    @GetMapping("/{enrollmentNo}")
    public ApiResult<Map<String, Object>> status(@PathVariable String enrollmentNo, Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.status(userId, enrollmentNo);
    }

    private Long authenticatedUserId(Authentication authentication) {
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

    public record CreateRequest(String requestedGpuModel) { }
}
