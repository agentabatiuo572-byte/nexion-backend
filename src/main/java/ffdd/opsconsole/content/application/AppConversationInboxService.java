package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.mapper.AppConversationInboxMapper;
import ffdd.opsconsole.content.mapper.AppConversationInboxMapper.Dismissal;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class AppConversationInboxService {
    private final AppConversationInboxMapper mapper;
    private final ProductionSupportPathGuard guard;

    public ApiResult<List<Dismissal>> list(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        guard.requireAllowed(userId);
        return ApiResult.ok(mapper.list(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Dismissal> dismiss(Long userId, String conversationNo, Long throughMessageId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        guard.requireAllowed(userId);
        if (conversationNo == null || conversationNo.isBlank() || conversationNo.length() > 40
                || throughMessageId == null || throughMessageId <= 0) {
            return ApiResult.fail(400, "CONVERSATION_DISMISSAL_INVALID");
        }
        if (!mapper.publicMessageExists(userId, conversationNo, throughMessageId)) {
            return ApiResult.fail(404, "CONVERSATION_MESSAGE_NOT_FOUND");
        }
        mapper.dismiss(userId, conversationNo, throughMessageId);
        Dismissal result = mapper.find(userId, conversationNo);
        if (result == null || result.throughMessageId() < throughMessageId) {
            return ApiResult.fail(409, "CONVERSATION_DISMISSAL_CONFLICT");
        }
        return ApiResult.ok(result);
    }
}
