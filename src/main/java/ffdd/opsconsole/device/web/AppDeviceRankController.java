package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.AppDeviceRankService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/app/network/device-rank")
@RequiredArgsConstructor
public class AppDeviceRankController {
    private final AppDeviceRankService service;

    @GetMapping
    public ApiResult<Map<String, Object>> snapshot(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        try { return service.snapshot(Long.parseLong(String.valueOf(auth.getPrincipal()))); }
        catch (NumberFormatException ex) { return ApiResult.fail(403, "USER_SUBJECT_REQUIRED"); }
    }
}
