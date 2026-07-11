package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppCopyExperimentService;
import ffdd.opsconsole.content.domain.AppCopyDeliveryView;
import ffdd.opsconsole.content.domain.AppExperimentConversionView;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class AppCopyExperimentController {
    private final AppCopyExperimentService service;

    @GetMapping("/copies/{copyKey}")
    public ApiResult<AppCopyDeliveryView> deliver(
            @PathVariable String copyKey,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null
                ? ApiResult.fail(403, "USER_AUTH_REQUIRED")
                : service.deliver(userId, copyKey);
    }

    @PostMapping("/experiments/{experimentId}/convert")
    public ApiResult<AppExperimentConversionView> convert(
            @PathVariable String experimentId,
            @RequestBody(required = false) AppExperimentConversionRequest request,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        return service.convert(userId, experimentId, request == null ? null : request.conversionKey());
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null) {
            return null;
        }
        if (!(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
        try {
            long userId = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
