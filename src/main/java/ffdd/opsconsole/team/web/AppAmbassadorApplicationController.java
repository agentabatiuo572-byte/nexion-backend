package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.AppAmbassadorApplicationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/team/ambassador-applications")
@RequiredArgsConstructor
public class AppAmbassadorApplicationController {
    private final AppAmbassadorApplicationService service;

    @PostMapping
    public ApiResult<Map<String, Object>> submit(@RequestBody(required = false) Request request,
                                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                  Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.submit(userId,
                request == null ? null : request.eventDate(), request == null ? null : request.city(),
                request == null ? null : request.budgetUsdt(), request == null ? null : request.bucket(), key);
    }

    @GetMapping("/latest")
    public ApiResult<Map<String, Object>> latest(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.latest(userId);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try { long value = Long.parseLong(String.valueOf(authentication.getPrincipal())); return value > 0 ? value : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    public record Request(LocalDate eventDate, String city, BigDecimal budgetUsdt, String bucket) { }
}
