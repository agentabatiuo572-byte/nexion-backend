package ffdd.opsconsole.janus.web;

import ffdd.opsconsole.janus.application.OpsJanusService;
import ffdd.opsconsole.janus.domain.JanusDeviceView;
import ffdd.opsconsole.janus.dto.JanusCommandAckRequest;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/janus")
@RequiredArgsConstructor
public class AppJanusController {
    private final OpsJanusService janusService;

    @PostMapping("/reports")
    public ApiResult<JanusDeviceView> report(@RequestBody JanusDeviceReportRequest request,
                                             Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : janusService.reportDevice(userId, request);
    }

    @GetMapping("/commands/pending")
    public ApiResult<Map<String, Object>> pending(@RequestParam String deviceId, Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : janusService.pendingCommand(userId, deviceId);
    }

    @PostMapping("/commands/ack")
    public ApiResult<Map<String, Object>> acknowledge(@RequestBody JanusCommandAckRequest request,
                                                       Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : janusService.acknowledgeCommand(userId, request);
    }

    private Long authenticatedUserId(Authentication authentication) {
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
