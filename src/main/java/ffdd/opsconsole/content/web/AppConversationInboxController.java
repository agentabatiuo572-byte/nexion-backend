package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppConversationInboxService;
import ffdd.opsconsole.content.mapper.AppConversationInboxMapper.Dismissal;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/app/support")
@RequiredArgsConstructor
public class AppConversationInboxController {
    private final AppConversationInboxService service;

    @GetMapping("/conversation-dismissals")
    public ApiResult<List<Dismissal>> list(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.list(userId);
    }

    @PostMapping("/conversations/{conversationNo}/dismiss")
    public ApiResult<Dismissal> dismiss(@PathVariable String conversationNo,
            @RequestBody DismissRequest request, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.dismiss(userId, conversationNo, request == null ? null : request.throughMessageId());
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))) return null;
        try {
            long id = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return id > 0 ? id : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    public record DismissRequest(Long throughMessageId) {}
}
