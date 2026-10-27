package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppConversationManagementService;
import ffdd.opsconsole.content.application.AppConversationManagementService.ClearRequest;
import ffdd.opsconsole.content.application.AppConversationManagementService.Management;
import ffdd.opsconsole.content.domain.ConversationUserClear;
import ffdd.opsconsole.shared.api.ApiResult;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/app/support")
@RequiredArgsConstructor
public class AppConversationManagementController {
    private final AppConversationManagementService service;

    @GetMapping("/conversation-management")
    public ApiResult<Management> management(Authentication authentication, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.management(userId(authentication));
    }

    @DeleteMapping("/conversations/{conversationNo}")
    public ApiResult<ConversationUserClear> clear(@PathVariable String conversationNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody ClearRequest request, Authentication authentication, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.clear(userId(authentication), conversationNo, key, request);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))) return null;
        try {
            long id = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) { return null; }
    }
}
