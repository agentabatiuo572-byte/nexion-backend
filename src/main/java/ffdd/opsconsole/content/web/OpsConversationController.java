package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationTicketResult;
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationFallbackRequest;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.dto.ConversationStatusRequest;
import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/conversations")
@RequiredArgsConstructor
public class OpsConversationController {
    private final OpsConversationService conversationService;

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return conversationService.overview();
    }

    @GetMapping
    public ApiResult<PageResult<ContentConversationView>> conversations(ConversationQueryRequest request) {
        return conversationService.conversations(request);
    }

    @PostMapping
    public ApiResult<ContentConversationView> initiate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationInitiateRequest request) {
        return conversationService.initiate(idempotencyKey, request);
    }

    @GetMapping("/transfer-targets")
    public ApiResult<List<Map<String, Object>>> transferTargets() {
        return conversationService.transferTargets();
    }

    @GetMapping("/{conversationNo}")
    public ApiResult<ContentConversationDetail> detail(@PathVariable String conversationNo) {
        return conversationService.detail(conversationNo);
    }

    @PostMapping("/{conversationNo}/transfer")
    public ApiResult<ContentConversationView> transfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferRequest request) {
        return conversationService.transfer(conversationNo, idempotencyKey, request);
    }

    @PostMapping("/{conversationNo}/replies")
    public ApiResult<ContentConversationView> reply(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationReplyRequest request) {
        return conversationService.reply(conversationNo, idempotencyKey, request);
    }

    @PatchMapping("/{conversationNo}/status")
    public ApiResult<ContentConversationView> updateStatus(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationStatusRequest request) {
        return conversationService.updateStatus(conversationNo, idempotencyKey, request);
    }

    @PatchMapping("/{conversationNo}/archive")
    public ApiResult<ContentConversationView> archive(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationArchiveRequest request) {
        return conversationService.archive(conversationNo, idempotencyKey, request);
    }

    @PostMapping("/{conversationNo}/transfer/fallback")
    public ApiResult<ContentConversationView> fallbackTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationFallbackRequest request) {
        return conversationService.fallbackTransfer(conversationNo, idempotencyKey, request);
    }

    @PostMapping("/{conversationNo}/ticket")
    public ApiResult<ConversationTicketResult> convertToTicket(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTicketRequest request) {
        return conversationService.convertToTicket(conversationNo, idempotencyKey, request);
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

    @PostMapping("/{conversationNo}/transfer/wait")
    public ApiResult<ContentConversationView> waitTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferDecisionRequest request) {
        return conversationService.waitTransfer(conversationNo, idempotencyKey, request);
    }
}
