package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportUnboundReceiptMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * M-20: after an assignment commits, invalidate the customer's latest advisor conversation so the new
 * advisor's console reloads it (and can notify) without waiting for the next poll.
 */
@Component
@RequiredArgsConstructor
public class SupportAssignmentNotifier {
    private final SupportUnboundReceiptMapper conversations;
    private final ffdd.opsconsole.content.mapper.ConversationMapper headers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void claimWelcome(SupportBindingService.SupportAssignmentChanged change) {
        if(change.current()==null || change.previous()!=null) return;
        String no=headers.unassignedWelcome(change.customerId());
        if(no!=null) headers.claimUnassignedPeer(no,change.current().agentAdminId().toString());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void assigned(SupportBindingService.SupportAssignmentChanged change) {
        if (change.current() == null) return;
        String no = conversations.latestAdvisorConversationNo(change.customerId());
        if (no == null) return;
        events.publishEvent(ConversationMessageEvent.builder().conversationNo(no)
                .eventType(ConversationMessageEvent.EventType.STATUS).senderType("SYSTEM").senderName("系统")
                .body("ASSIGNMENT_CHANGED").ts(LocalDateTime.now(clock))
                .ownerAgentId(String.valueOf(change.current().agentAdminId())).build());
    }
}
