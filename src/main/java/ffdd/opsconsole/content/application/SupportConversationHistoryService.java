package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.content.domain.ContentConversationMessagePage.*;
import ffdd.opsconsole.content.mapper.ConversationHistoryMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class SupportConversationHistoryService {
    private final ConversationRepository conversations;
    private final ConversationHistoryMapper mapper;
    private final SupportOwnershipService ownership;
    private final ProductionSupportPathGuard production;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentConversationMessagePage page(String no,Long beforeMessageId) {
        if(no==null || no.isBlank())throw new BizException(422,"CONVERSATION_NO_REQUIRED");
        if(beforeMessageId!=null)SupportWorkbenchService.requireSafeId(beforeMessageId);
        ownership.readConversation(no);production.requireOpsWriteAllowed();
        if(mapper.currentReadGrants(ownership.actorId()).isEmpty())throw new BizException(403,"SUPPORT_SCOPE_FORBIDDEN");
        var conversation=conversations.findByConversationNo(no).orElseThrow(()->new BizException(404,"CONVERSATION_NOT_FOUND"));
        Long customer=conversation.userId();
        if(customer==null || customer<=0 || !Set.of("advisor","support").contains(conversation.conversationType()))throw new BizException(404,"CONVERSATION_NOT_FOUND");
        var cursor=beforeMessageId==null?null:mapper.cursor(customer,beforeMessageId);
        if(beforeMessageId!=null && (cursor==null || cursor.createdAt()==null))throw new BizException(404,"CONVERSATION_CURSOR_NOT_FOUND");
        var identity=mapper.identity(customer);
        if(identity==null || identity.customerId()!=customer)throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
        var newest=Objects.requireNonNull(mapper.page(customer,cursor==null?null:cursor.createdAt(),beforeMessageId));
        if(newest.size()>11)throw new IllegalStateException("CONVERSATION_PAGE_LIMIT_INVALID");
        boolean more=newest.size()>10;
        var messages=new ArrayList<>(newest.subList(0,Math.min(10,newest.size())));
        Collections.reverse(messages);
        var userIds=messages.stream().filter(m->"user".equals(m.senderType())).map(ContentConversationMessageView::id).toList();
        var readContexts=userIds.isEmpty()?List.<ReadContext>of():mapper.readContexts(customer,userIds);
        var reply=new ReplyContext(true,List.of());
        if(SupportOwnershipService.hasAuthority("service_m3_write")) {
            try {
                ownership.requireWriter(customer,false);
                reply=new ReplyContext(true,mapper.replyTargets(customer));
            } catch(BizException denied) {
                if(denied.getCode()!=403 && denied.getCode()!=404)throw denied;
            } catch(DataAccessException failed) {reply=new ReplyContext(false,List.of());}
        }
        ownership.readConversation(no);
        return new ContentConversationMessagePage(conversation,messages,more,more?messages.get(0).id():null,identity,reply,readContexts);
    }
}
