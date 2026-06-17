package ffdd.opsconsole.content.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/conversations")
public class OpsConversationController {
    private final OpsConversationService conversationService;

    public OpsConversationController(OpsConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return conversationService.overview();
    }

    @GetMapping
    public ApiResult<PageResult<ContentConversationView>> conversations(ConversationQueryRequest request) {
        return conversationService.conversations(request);
    }

    @GetMapping("/transfer-targets")
    public ApiResult<List<Map<String, Object>>> transferTargets() {
        return conversationService.transferTargets();
    }

    @GetMapping("/{conversationNo}")
    public ApiResult<ContentConversationView> detail(@PathVariable String conversationNo) {
        return conversationService.detail(conversationNo);
    }

    @PostMapping("/{conversationNo}/transfer")
    public ApiResult<ContentConversationView> transfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferRequest request) {
        return conversationService.transfer(conversationNo, idempotencyKey, request);
    }

    @PostMapping("/{conversationNo}/transfer/accept")
    public ApiResult<ContentConversationView> acceptTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferDecisionRequest request) {
        return conversationService.acceptTransfer(conversationNo, idempotencyKey, request);
    }

    @PostMapping("/{conversationNo}/transfer/return")
    public ApiResult<ContentConversationView> returnTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferDecisionRequest request) {
        return conversationService.returnTransfer(conversationNo, idempotencyKey, request);
    }
}
