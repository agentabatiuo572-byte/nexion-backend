package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppNovaAiService;
import ffdd.opsconsole.content.application.NovaHumanHandoffService;
import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/support/ai")
@RequiredArgsConstructor
public class AppNovaAiController {
    private final AppNovaAiService service;
    private final NovaHumanHandoffService handoffService;

    @PostMapping("/handoffs")
    public ApiResult<ContentConversationDetail> handoff(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NovaHumanHandoffService.NovaHandoffConfirmRequest request,
            Authentication authentication) {
        return handoffService.confirm(userId(authentication), idempotencyKey, request);
    }

    @GetMapping("/status")
    public ApiResult<AppNovaAiService.Status> status(Authentication authentication) {
        return ApiResult.ok(service.status(userId(authentication)));
    }

    @PostMapping("/chat")
    public ApiResult<AppNovaAiService.ChatResponse> chat(
            @Valid @RequestBody NovaAiChatRequest request,
            Authentication authentication) {
        return ApiResult.ok(service.chat(userId(authentication), userSessionId(authentication), request));
    }

    @GetMapping("/history")
    public ApiResult<AppNovaAiService.HistoryResponse> history(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String beforeTurnId,
            Authentication authentication) {
        return ApiResult.ok(service.history(userId(authentication), conversationId, beforeTurnId));
    }

    private Long userId(Authentication authentication) {
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

    private String userSessionId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        Object value = details.get("sessionId");
        return value == null ? null : String.valueOf(value).trim();
    }
}
