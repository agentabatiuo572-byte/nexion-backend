package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import lombok.RequiredArgsConstructor;

@ApplicationService
@RequiredArgsConstructor
public class SupportReplyService {
    private final SupportBindingMapper mapper;
    private final SupportOwnershipService ownership;

    public void requireHandled(String no) {
        if(mapper.pendingReplies(no)>0) throw new BizException(409,"SUPPORT_REPLY_REQUIRED_BEFORE_CLOSE");
    }

    public void handled(Long customer,String current,Long messageId,ConversationReplyRequest request) {
        List<ConversationReplyRequest.ReplyTarget> targets=request.replyTargets();
        if(targets==null && request.replyThroughMessageId()!=null)
            targets=List.of(new ConversationReplyRequest.ReplyTarget(current,request.replyThroughMessageId()));
        if(targets==null || targets.isEmpty()) return;
        if(targets.size()>100) throw new BizException(422,"SUPPORT_REPLY_TARGETS_INVALID");
        ownership.requireWriter(customer,true);
        Set<String> seen=new HashSet<>();
        for(var t:targets) {
            if(t==null || t.conversationNo()==null || !seen.add(t.conversationNo()) || t.throughMessageId()==null
                    || !customer.equals(mapper.conversationCustomer(t.conversationNo())) || mapper.customerMessage(t.conversationNo(),t.throughMessageId())!=1)
                throw new BizException(422,"SUPPORT_REPLY_TARGET_INVALID");
        }
        for(var t:targets) mapper.handled(t.conversationNo(),t.throughMessageId(),messageId);
    }
}
