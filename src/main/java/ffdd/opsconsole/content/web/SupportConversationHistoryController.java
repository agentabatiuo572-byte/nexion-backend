package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.SupportConversationHistoryService;
import ffdd.opsconsole.content.domain.ContentConversationMessagePage;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX+"/content/conversations")
@RequiredArgsConstructor
public class SupportConversationHistoryController {
    private final SupportConversationHistoryService history;
    @GetMapping("/{conversationNo}/messages")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ApiResult<ContentConversationMessagePage> messages(@PathVariable String conversationNo,@RequestParam(required=false) Long beforeMessageId) {
        return ApiResult.ok(history.page(conversationNo,beforeMessageId));
    }
}
