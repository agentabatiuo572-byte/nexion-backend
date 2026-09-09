package ffdd.opsconsole.content.realtime;

import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.web.AppSupportController.MarkReadRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class ConversationAdminReadService {
    private final ConversationRepository conversations;
    private final ApplicationEventPublisher events;
    private final ProductionSupportPathGuard production;
    private final Clock clock;

    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Void> read(String no, MarkReadRequest request, String actor) {
        production.requireOpsWriteAllowed();
        if (request == null || request.lastSeenMessageId() == null || request.lastSeenMessageId() <= 0)
            return ApiResult.fail(400,"LAST_SEEN_MESSAGE_ID_REQUIRED");
        var c=conversations.findByConversationNoForUpdate(no).orElse(null);
        if(c==null) return ApiResult.fail(404,"CONVERSATION_NOT_FOUND");
        if(!Objects.equals(c.version(),request.expectedVersion()) || !c.status().equalsIgnoreCase(String.valueOf(request.expectedStatus())))
            return ApiResult.fail(409,"CONVERSATION_STATE_CONFLICT");
        if(conversations.messages(no).stream().noneMatch(m -> request.lastSeenMessageId().equals(m.id()) && "user".equalsIgnoreCase(m.senderType())))
            return ApiResult.fail(404,"CONVERSATION_USER_MESSAGE_NOT_FOUND");
        var now=LocalDateTime.now(clock);
        if(conversations.markUserMessagesReadThrough(c,request.lastSeenMessageId(),"admin:"+actor,now)) {
            var event=ConversationMessageEvent.builder().conversationNo(no).messageId(request.lastSeenMessageId())
                    .eventType(ConversationMessageEvent.EventType.RECEIPT).senderType("SYSTEM").ts(now).build();
            if(TransactionSynchronizationManager.isSynchronizationActive())
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void afterCommit(){events.publishEvent(event);}
                });
            else events.publishEvent(event);
        }
        return ApiResult.ok();
    }
}
