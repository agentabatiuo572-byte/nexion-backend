package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppSupportService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/content/app/conversations")
@RequiredArgsConstructor
public class AppConversationReceiptController {
    private final AppSupportService supportService;
    private final ProductionSupportPathGuard productionPathGuard;

    @PostMapping("/{conversationNo}/receipts/read")
    public ApiResult<Void> markReadReceipt(
            @PathVariable String conversationNo,
            @RequestBody AppConversationReceiptRequest request,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        productionPathGuard.requireAllowed(userId);
        Long lastSeenMessageId = request == null ? null : request.lastSeenMessageId();
        var result = supportService.markConversationRead(userId, conversationNo, lastSeenMessageId,
                request == null ? null : request.expectedStatus(),
                request == null ? null : request.expectedVersion());
        return result.getCode() == 0
                ? ApiResult.ok()
                : ApiResult.fail(result.getCode(), result.getMessage());
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
            return Long.valueOf(String.valueOf(authentication.getPrincipal()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
