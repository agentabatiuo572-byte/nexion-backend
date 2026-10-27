package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.mapper.SupportTicketNoticeMapper;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * D-8: tells the customer in their dedicated conversation that a ticket moved on. The notice goes into the
 * latest advisor segment even when it has ended (no new segment is opened) and, as a system message, never
 * counts as an advisor reply. It carries only the ticket number, never ticket content.
 */
@Component
@RequiredArgsConstructor
public class SupportTicketProgressNotice {
    private static final Map<String, String> STATUS_TEXT = Map.of(
            "IN_PROGRESS", "已受理，正在处理",
            "PENDING_USER", "需要您补充信息，请到工单中回复",
            "RESOLVED", "已解决",
            "CLOSED", "已关闭");
    private final SupportTicketNoticeMapper mapper;
    private final ApplicationEventPublisher events;

    public void statusChanged(SupportTicketView ticket, String status, LocalDateTime now) {
        String text = STATUS_TEXT.get(status);
        if (text != null) send(ticket, "您的工单 " + ticket.ticketNo() + " " + text + "。", now);
    }

    public void replied(SupportTicketView ticket, LocalDateTime now) {
        send(ticket, "您的工单 " + ticket.ticketNo() + " 有新的客服回复，请到工单中查看。", now);
    }

    private void send(SupportTicketView ticket, String text, LocalDateTime now) {
        if (ticket.userId() == null || ticket.userId() <= 0) return;
        String conversationNo = mapper.latestAdvisorConversationNo(ticket.userId());
        if (conversationNo == null || mapper.insertNotice(conversationNo, text, now) != 1) return;
        ConversationMessageEvent event = ConversationMessageEvent.builder()
                .conversationNo(conversationNo).eventType(ConversationMessageEvent.EventType.MESSAGE)
                .senderType("SYSTEM").senderName("系统").body(text).ts(now).build();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publishEvent(event);
            }
        });
    }
}
