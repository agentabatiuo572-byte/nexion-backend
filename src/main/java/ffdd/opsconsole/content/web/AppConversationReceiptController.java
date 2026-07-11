package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final OpsConversationService conversationService;
    private final ApplicationEventPublisher eventPublisher;

    @PostMapping("/{conversationNo}/receipts/read")
    public ApiResult<Void> markReadReceipt(
            @PathVariable String conversationNo,
            @RequestBody AppConversationReceiptRequest request,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        Long lastSeenMessageId = request == null ? null : request.lastSeenMessageId();
        ApiResult<Void> result = conversationService.markReadReceipt(conversationNo, lastSeenMessageId, userId);
        if (result.getCode() == 0) {
            eventPublisher.publishEvent(ConversationMessageEvent.builder()
                    .conversationNo(conversationNo)
                    .messageId(lastSeenMessageId)
                    .eventType(ConversationMessageEvent.EventType.RECEIPT)
                    .senderType("USER")
                    .senderName("user:" + userId)
                    .body("read")
                    .ts(LocalDateTime.now())
                    .build());
        }
        return result;
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
