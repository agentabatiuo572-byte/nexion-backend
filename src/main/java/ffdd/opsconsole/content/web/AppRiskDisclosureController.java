package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppRiskDisclosureService;
import ffdd.opsconsole.content.domain.AppRiskDisclosureView;
import ffdd.opsconsole.content.dto.AppRiskDisclosureAckRequest;
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
@RequestMapping("/api/legal/risk-disclosure")
@RequiredArgsConstructor
public class AppRiskDisclosureController {
    private final AppRiskDisclosureService service;

    @GetMapping("/current")
    public ApiResult<AppRiskDisclosureView> current(Authentication authentication) {
        return service.current(authenticatedUserId(authentication));
    }

    @PostMapping("/acknowledgment")
    public ApiResult<AppRiskDisclosureView> acknowledge(
            @RequestBody AppRiskDisclosureAckRequest request, Authentication authentication) {
        return service.acknowledge(authenticatedUserId(authentication), request);
    }

    @PostMapping("/gates/{actionKey}/check")
    public ApiResult<Void> checkGate(@PathVariable String actionKey, Authentication authentication) {
        return service.checkGate(authenticatedUserId(authentication), actionKey);
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long userId = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
