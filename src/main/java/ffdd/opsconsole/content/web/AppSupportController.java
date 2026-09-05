package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppSupportService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationTicketResult;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportTicketDetail;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/support")
@RequiredArgsConstructor
public class AppSupportController {
    private final AppSupportService service;
    private final ProductionSupportPathGuard productionPathGuard;

    @GetMapping("/tickets")
    public ApiResult<PageResult<SupportTicketView>> tickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.tickets(userId, status, pageNum, pageSize) : forbidden();
    }

    @GetMapping("/tickets/cursor")
    public ApiResult<PageResult<SupportTicketView>> ticketCursor(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Long pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.ticketCursor(userId, status, beforeId, pageSize) : forbidden();
    }

    @PostMapping("/tickets/{ticketNo}/read")
    public ApiResult<SupportTicketDetail> markTicketRead(@PathVariable String ticketNo,
            @RequestBody AppSupportService.CloseRequest request, Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.markTicketRead(userId, ticketNo, request) : forbidden();
    }

    @GetMapping("/tickets/{ticketNo}")
    public ApiResult<SupportTicketDetail> ticket(@PathVariable String ticketNo,
            @RequestParam(required = false) Long beforeMessageId, Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.ticket(userId, ticketNo, beforeMessageId) : forbidden();
    }

    @PostMapping("/tickets")
    public ApiResult<SupportTicketDetail> createTicket(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.CreateTicketRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.createTicket(userId, idempotencyKey, request) : forbidden();
    }

    @PostMapping("/tickets/{ticketNo}/replies")
    public ApiResult<SupportTicketDetail> replyTicket(
            @PathVariable String ticketNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.ReplyRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.replyTicket(userId, ticketNo, idempotencyKey, request) : forbidden();
    }

    @PostMapping("/tickets/{ticketNo}/close")
    public ApiResult<SupportTicketDetail> closeTicket(
            @PathVariable String ticketNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.CloseRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.closeTicket(userId, ticketNo, idempotencyKey, request) : forbidden();
    }

    @GetMapping("/conversations")
    public ApiResult<PageResult<ContentConversationView>> conversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.conversations(userId, status, pageNum, pageSize) : forbidden();
    }

    @GetMapping("/conversations/cursor")
    public ApiResult<PageResult<ContentConversationView>> conversationCursor(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Long pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.conversationCursor(userId, status, beforeId, pageSize) : forbidden();
    }

    @GetMapping("/conversation-categories")
    public ApiResult<List<AppSupportService.ConversationCategoryAvailability>> conversationCategories(
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.conversationCategories(userId) : forbidden();
    }

    @GetMapping("/conversations/{conversationNo}")
    public ApiResult<ContentConversationDetail> conversation(
            @PathVariable String conversationNo, @RequestParam(required = false) Long beforeMessageId,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.conversation(userId, conversationNo, beforeMessageId) : forbidden();
    }

    @PostMapping("/conversations/{conversationNo}/read")
    public ApiResult<ContentConversationDetail> markConversationRead(
            @PathVariable String conversationNo,
            @RequestBody MarkReadRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId)
                ? service.markConversationRead(userId, conversationNo,
                        request == null ? null : request.lastSeenMessageId(),
                        request == null ? null : request.expectedStatus(),
                        request == null ? null : request.expectedVersion())
                : forbidden();
    }

    @PostMapping("/conversations")
    public ApiResult<ContentConversationDetail> startConversation(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.StartConversationRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.startConversation(userId, idempotencyKey, request) : forbidden();
    }

    @PostMapping("/conversations/{conversationNo}/replies")
    public ApiResult<ContentConversationDetail> replyConversation(
            @PathVariable String conversationNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.ReplyRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.replyConversation(userId, conversationNo, idempotencyKey, request) : forbidden();
    }

    @PostMapping("/conversations/{conversationNo}/ticket")
    public ApiResult<ConversationTicketResult> convertConversationToTicket(
            @PathVariable String conversationNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.ConvertToTicketRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId)
                ? service.convertConversationToTicket(userId, conversationNo, idempotencyKey, request)
                : forbidden();
    }

    @GetMapping("/faqs")
    public ApiResult<List<SupportFaqView>> faqs(
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String surface,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.faqs(userId, language, category, surface) : forbidden();
    }

    @GetMapping("/faqs/page")
    public ApiResult<ffdd.opsconsole.shared.api.PageResult<SupportFaqView>> faqPage(
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String surface,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.faqPage(userId, language, category, surface, pageNum, pageSize) : forbidden();
    }

    // Kept for direct controller callers compiled against the original three-argument contract.
    ApiResult<List<SupportFaqView>> faqs(String language, String category, Authentication authentication) {
        return faqs(language, category, null, authentication);
    }

    @GetMapping("/sla-targets")
    public ApiResult<List<AppSupportService.AppSupportSlaTarget>> slaTargets(Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.slaTargets(userId) : forbidden();
    }

    @GetMapping("/commands/{key}")
    public ApiResult<Map<String, Object>> commandResult(@PathVariable String key, Authentication authentication) {
        Long userId = userId(authentication);
        return guarded(userId) ? service.commandResult(userId, key) : forbidden();
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

    private <T> ApiResult<T> forbidden() {
        return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
    }

    private boolean guarded(Long userId) {
        if (userId == null) return false;
        productionPathGuard.requireAllowed(userId);
        return true;
    }

    public record MarkReadRequest(Long lastSeenMessageId, String expectedStatus, Long expectedVersion) {}
}
