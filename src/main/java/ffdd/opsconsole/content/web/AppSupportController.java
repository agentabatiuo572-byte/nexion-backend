package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppSupportService;
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

    @GetMapping("/tickets")
    public ApiResult<PageResult<SupportTicketView>> tickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.tickets(userId, status, pageNum, pageSize);
    }

    @GetMapping("/tickets/{ticketNo}")
    public ApiResult<SupportTicketDetail> ticket(@PathVariable String ticketNo, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.ticket(userId, ticketNo);
    }

    @PostMapping("/tickets")
    public ApiResult<SupportTicketDetail> createTicket(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.CreateTicketRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.createTicket(userId, idempotencyKey, request);
    }

    @PostMapping("/tickets/{ticketNo}/replies")
    public ApiResult<SupportTicketDetail> replyTicket(
            @PathVariable String ticketNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.ReplyRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.replyTicket(userId, ticketNo, idempotencyKey, request);
    }

    @PostMapping("/tickets/{ticketNo}/close")
    public ApiResult<SupportTicketDetail> closeTicket(
            @PathVariable String ticketNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.CloseRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.closeTicket(userId, ticketNo, idempotencyKey, request);
    }

    @GetMapping("/conversations")
    public ApiResult<PageResult<ContentConversationView>> conversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.conversations(userId, status, pageNum, pageSize);
    }

    @GetMapping("/conversations/{conversationNo}")
    public ApiResult<ContentConversationDetail> conversation(
            @PathVariable String conversationNo, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.conversation(userId, conversationNo);
    }

    @PostMapping("/conversations/{conversationNo}/read")
    public ApiResult<ContentConversationDetail> markConversationRead(
            @PathVariable String conversationNo,
            @RequestBody MarkReadRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.markConversationRead(userId, conversationNo, request == null ? null : request.lastSeenMessageId());
    }

    @PostMapping("/conversations")
    public ApiResult<ContentConversationDetail> startConversation(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.StartConversationRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.startConversation(userId, idempotencyKey, request);
    }

    @PostMapping("/conversations/{conversationNo}/replies")
    public ApiResult<ContentConversationDetail> replyConversation(
            @PathVariable String conversationNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.ReplyRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.replyConversation(userId, conversationNo, idempotencyKey, request);
    }

    @PostMapping("/conversations/{conversationNo}/ticket")
    public ApiResult<ConversationTicketResult> convertConversationToTicket(
            @PathVariable String conversationNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AppSupportService.ConvertToTicketRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.convertConversationToTicket(userId, conversationNo, idempotencyKey, request);
    }

    @GetMapping("/faqs")
    public ApiResult<List<SupportFaqView>> faqs(
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String category,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.faqs(userId, language, category);
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

    public record MarkReadRequest(Long lastSeenMessageId) {}
}
