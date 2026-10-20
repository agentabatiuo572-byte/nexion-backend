package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.common.boundary.PhoneReplacementSupportContract;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PhoneReplacementSupportFacade implements PhoneReplacementSupportContract {
    private final ConversationMapper conversations;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireConversationOwner(long userId, String conversationNo) {
        if (conversationNo == null || conversationNo.isBlank() || conversationNo.length() > 128)
            throw new BizException(422, "SUPPORT_REFERENCE_INVALID");
        var conversation = conversations.findCurrentByConversationNo(conversationNo);
        if (conversation == null || !Long.valueOf(userId).equals(conversation.userId()))
            throw new BizException(403, "PERMISSION_DENIED");
    }
}
