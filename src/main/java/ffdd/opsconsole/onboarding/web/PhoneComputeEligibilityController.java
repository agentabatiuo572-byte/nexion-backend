package ffdd.opsconsole.onboarding.web;

import ffdd.opsconsole.onboarding.application.PhoneComputeEligibilityService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PhoneComputeEligibilityController {
    private final PhoneComputeEligibilityService service;

    @GetMapping("/api/tasks/phone/eligibility")
    public ApiResult<PhoneComputeEligibilityService.Eligibility> eligibility(Authentication auth) {
        if (auth==null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details)
                || !"USER".equals(details.get("subjectType"))) throw new BizException(403,"USER_SUBJECT_REQUIRED");
        return ApiResult.ok(service.read(Long.parseLong(String.valueOf(auth.getPrincipal()))));
    }
}
