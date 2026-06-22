package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.content.facade.ContentSearchFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ContentSearchFacadeAdapter implements ContentSearchFacade {
    private final OpsSupportTicketService ticketService;
    private final OpsConversationService conversationService;

    @Override
    public List<AdminSearchHit> searchSupportTickets(String keyword, int limit) {
        String q = trim(keyword);
        if (!StringUtils.hasText(q)) {
            return List.of();
        }
        ApiResult<PageResult<SupportTicketView>> result = ticketService.tickets(
                new SupportTicketQueryRequest("all", null, null, null, null, null, q, 1L, (long) limit));
        PageResult<SupportTicketView> page = result == null ? null : result.getData();
        if (result == null || result.getCode() != 0 || page == null || page.getRecords() == null) {
            return List.of();
        }
        return page.getRecords().stream()
                .map(ticket -> new AdminSearchHit(
                        "ticket",
                        ticket.ticketNo(),
                        ticket.ticketNo() + " · " + text(ticket.title(), "未命名工单"),
                        join(ticket.category(), ticket.priority(), ticket.status(), assigned(ticket.assignedAdminName())),
                        "/service/tickets?ticket=" + ticket.ticketNo(),
                        exactScore(q, ticket.ticketNo(), ticket.title(), ticket.assignedAdminName())))
                .toList();
    }

    @Override
    public List<AdminSearchHit> searchConversations(String keyword, int limit) {
        String q = trim(keyword);
        if (!StringUtils.hasText(q)) {
            return List.of();
        }
        ApiResult<PageResult<ContentConversationView>> result = conversationService.conversations(
                new ConversationQueryRequest(null, null, null, null, q, null, 1L, (long) limit));
        PageResult<ContentConversationView> page = result == null ? null : result.getData();
        if (result == null || result.getCode() != 0 || page == null || page.getRecords() == null) {
            return List.of();
        }
        return page.getRecords().stream()
                .map(conversation -> new AdminSearchHit(
                        "conversation",
                        conversation.conversationNo(),
                        conversation.conversationNo() + " · " + text(
                                firstText(conversation.ownerAgentName(), conversation.ownerAgentId()),
                                "未分配坐席"),
                        join(
                                text(conversation.conversationType(), "conversation"),
                                conversation.status(),
                                conversation.userId() == null ? null : "用户 " + conversation.userId(),
                                conversation.lastMessage()),
                        "/service/sessions?conversation=" + conversation.conversationNo(),
                        exactScore(q, conversation.conversationNo(), conversation.ownerAgentName(), conversation.ownerAgentId())))
                .toList();
    }

    private String assigned(String assignedAdminName) {
        return StringUtils.hasText(assignedAdminName) ? "负责人 " + assignedAdminName.trim() : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : second;
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String join(String... values) {
        return java.util.Arrays.stream(values)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
    }

    private int exactScore(String keyword, Object... values) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        for (Object value : values) {
            if (needle.equals(trim(value).toLowerCase(Locale.ROOT))) {
                return 0;
            }
        }
        return 1;
    }

    private String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
