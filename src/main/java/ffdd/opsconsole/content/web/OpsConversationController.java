package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationCustomerProfile;
import ffdd.opsconsole.content.domain.ConversationTicketResult;
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationArchiveBatchRequest;
import ffdd.opsconsole.content.dto.ConversationFallbackRequest;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.dto.ConversationStatusRequest;
import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRemoveRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRequest;
import ffdd.opsconsole.content.dto.CustomerTagRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    /**
     * 服务内事件总线：各写端点调完 service 后用它发布 ConversationMessageEvent，
     * OpsConversationStreamController 通过 @EventListener 接收并 SSE 推送给在线坐席。
     * 跨进程（app 端用户发消息）未来走 RocketMQ（shared/rocketmq outbox），不在此处处理。
     */
    private final ApplicationEventPublisher eventPublisher;
    private final AdminIdempotencyService idempotencyService;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> executeCommand(String scope, String idempotencyKey, String requestHash, java.util.function.Supplier<ApiResult<T>> action) {
        try {
            if (idempotencyKey == null || idempotencyKey.isBlank()) return action.get();
            return (ApiResult<T>) idempotencyService.execute(scope, idempotencyKey.trim(), requestHash, ApiResult.class, (java.util.function.Supplier) action);
        } catch (OpsConversationService.ConversationStateConflictException ignored) {
            return ApiResult.fail(ffdd.opsconsole.common.api.OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    ffdd.opsconsole.common.api.OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
    }

    private String requestHash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // 会话总览 — M3 即时会话台 读
    @PreAuthorize("hasAuthority('service_m3_read')")
    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return conversationService.overview();
    }

    // 收件箱/会话列表 — M3 即时会话台 读
    @PreAuthorize("hasAuthority('service_m3_read')")
    @GetMapping
    public ApiResult<PageResult<ContentConversationView>> conversations(ConversationQueryRequest request) {
        return conversationService.conversations(request);
    }

    // 主动发起会话 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping
    public ApiResult<ContentConversationView> initiate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationInitiateRequest request) {
        return executeCommand("M3_CONVERSATION_INITIATE", idempotencyKey, requestHash(String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.initiate(idempotencyKey, request);
            publishMessage(result.getData(), ConversationMessageEvent.EventType.INITIATE, "AGENT", null);
            return result;
        });
    }

    // 转交目标列表 — M3 即时会话台 读
    @PreAuthorize("hasAuthority('service_m3_read')")
    @GetMapping("/transfer-targets")
    public ApiResult<List<Map<String, Object>>> transferTargets() {
        return conversationService.transferTargets();
    }

    // 会话详情/客户档案 — M3 即时会话台 读
    @PreAuthorize("hasAuthority('service_m3_read')")
    @GetMapping("/{conversationNo}")
    public ApiResult<ContentConversationDetail> detail(@PathVariable String conversationNo) {
        return conversationService.detail(conversationNo);
    }

    @PreAuthorize("hasAuthority('service_m3_write')")
    @PatchMapping("/archive/batch")
    public ApiResult<List<ContentConversationView>> archiveBatch(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationArchiveBatchRequest request) {
        return executeCommand("M3_CONVERSATION_ARCHIVE_BATCH", idempotencyKey, requestHash(String.valueOf(request)), () -> {
            ApiResult<List<ContentConversationView>> result = conversationService.archiveBatch(idempotencyKey, request);
            if (result.getData() != null) result.getData().forEach(view -> publishStatus(view, "ARCHIVED"));
            return result;
        });
    }

    // 转交会话 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/transfer")
    public ApiResult<ContentConversationView> transfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferRequest request) {
        return executeCommand("M3_CONVERSATION_TRANSFER", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.transfer(conversationNo, idempotencyKey, request);
            publishTransfer(result.getData(), "TRANSFER");
            return result;
        });
    }

    // 会话回复 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/replies")
    public ApiResult<ContentConversationView> reply(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationReplyRequest request) {
        return executeCommand("M3_CONVERSATION_REPLY", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.reply(conversationNo, idempotencyKey, request);
            publishMessage(result.getData(), ConversationMessageEvent.EventType.MESSAGE, "AGENT", null);
            return result;
        });
    }

    // 会话状态变更 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PatchMapping("/{conversationNo}/status")
    public ApiResult<ContentConversationView> updateStatus(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationStatusRequest request) {
        return executeCommand("M3_CONVERSATION_STATUS", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.updateStatus(conversationNo, idempotencyKey, request);
            publishStatus(result.getData(), "STATUS_UPDATE");
            return result;
        });
    }

    // 会话归档 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PatchMapping("/{conversationNo}/archive")
    public ApiResult<ContentConversationView> archive(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationArchiveRequest request) {
        return executeCommand("M3_CONVERSATION_ARCHIVE", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.archive(conversationNo, idempotencyKey, request);
            publishStatus(result.getData(), "ARCHIVED");
            return result;
        });
    }

    // 退回/兜底转交 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/transfer/fallback")
    public ApiResult<ContentConversationView> fallbackTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationFallbackRequest request) {
        return executeCommand("M3_CONVERSATION_FALLBACK", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.fallbackTransfer(conversationNo, idempotencyKey, request);
            publishTransfer(result.getData(), "FALLBACK");
            return result;
        });
    }

    // 会话转工单 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/ticket")
    public ApiResult<ConversationTicketResult> convertToTicket(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTicketRequest request) {
        return executeCommand("M3_CONVERSATION_TO_TICKET", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ConversationTicketResult> result = conversationService.convertToTicket(conversationNo, idempotencyKey, request);
            if (result.getData() != null) eventPublisher.publishEvent(ConversationMessageEvent.builder()
                    .conversationNo(conversationNo).eventType(ConversationMessageEvent.EventType.STATUS)
                    .senderType("SYSTEM").senderName("系统").body("CONVERTED_TO_TICKET")
                    .ts(LocalDateTime.now()).build());
            return result;
        });
    }

    // 接收转交 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/transfer/accept")
    public ApiResult<ContentConversationView> acceptTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferDecisionRequest request) {
        return executeCommand("M3_CONVERSATION_TRANSFER_ACCEPT", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.acceptTransfer(conversationNo, idempotencyKey, request);
            publishTransfer(result.getData(), "TRANSFER_ACCEPTED");
            return result;
        });
    }

    // 退回转交 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/transfer/return")
    public ApiResult<ContentConversationView> returnTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferDecisionRequest request) {
        return executeCommand("M3_CONVERSATION_TRANSFER_RETURN", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.returnTransfer(conversationNo, idempotencyKey, request);
            publishTransfer(result.getData(), "TRANSFER_RETURNED");
            return result;
        });
    }

    // 转交等待 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/transfer/wait")
    public ApiResult<ContentConversationView> waitTransfer(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTransferDecisionRequest request) {
        return executeCommand("M3_CONVERSATION_TRANSFER_WAIT", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> {
            ApiResult<ContentConversationView> result = conversationService.waitTransfer(conversationNo, idempotencyKey, request);
            publishTransfer(result.getData(), "TRANSFER_WAITED");
            return result;
        });
    }

    // 添加客户标签 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/customer-tags")
    public ApiResult<List<String>> addCustomTag(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CustomerTagRequest request) {
        return executeCommand("M3_CUSTOMER_TAG_ADD", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> conversationService.addCustomTag(conversationNo, idempotencyKey, request));
    }

    // 移除客户标签 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @DeleteMapping("/{conversationNo}/customer-tags")
    public ApiResult<List<String>> removeCustomTag(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CustomerTagRequest request) {
        return executeCommand("M3_CUSTOMER_TAG_REMOVE", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> conversationService.removeCustomTag(conversationNo, idempotencyKey, request));
    }

    // 添加客户备注 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PostMapping("/{conversationNo}/customer-notes")
    public ApiResult<ConversationCustomerProfile.CustomerNote> addNote(
            @PathVariable String conversationNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CustomerNoteRequest request) {
        return executeCommand("M3_CUSTOMER_NOTE_ADD", idempotencyKey, requestHash(conversationNo, String.valueOf(request)), () -> conversationService.addNote(conversationNo, idempotencyKey, request));
    }

    // 移除客户备注 — M3 即时会话台 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @DeleteMapping("/{conversationNo}/customer-notes/{noteId}")
    public ApiResult<Void> removeNote(
            @PathVariable String conversationNo,
            @PathVariable Long noteId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CustomerNoteRemoveRequest request) {
        return executeCommand("M3_CUSTOMER_NOTE_REMOVE", idempotencyKey, requestHash(conversationNo, String.valueOf(noteId), String.valueOf(request)), () -> conversationService.removeNote(conversationNo, noteId, idempotencyKey, request));
    }

    /* ============ ConversationMessageEvent 发布辅助 ============ */

    /** 消息 / 主动发起：senderType=AGENT，正文取 lastMessage。 */
    private void publishMessage(ContentConversationView view, ConversationMessageEvent.EventType type, String senderType, String bodyOverride) {
        if (view == null || view.conversationNo() == null) {
            return;
        }
        eventPublisher.publishEvent(ConversationMessageEvent.builder()
                .conversationNo(view.conversationNo())
                .eventType(type)
                .senderType(senderType)
                .senderName(view.ownerAgentName())
                .body(bodyOverride != null ? bodyOverride : view.lastMessage())
                .ts(view.lastMessageAt() != null ? view.lastMessageAt() : LocalDateTime.now())
                .ownerAgentId(view.ownerAgentId())
                .ownerAgentName(view.ownerAgentName())
                .build());
    }

    /** 转交类：senderType=SYSTEM，body 写转交摘要（前端据此重渲 transfer 态）。 */
    private void publishTransfer(ContentConversationView view, String summary) {
        if (view == null || view.conversationNo() == null) {
            return;
        }
        eventPublisher.publishEvent(ConversationMessageEvent.builder()
                .conversationNo(view.conversationNo())
                .eventType(ConversationMessageEvent.EventType.TRANSFER)
                .senderType("SYSTEM")
                .senderName("系统")
                .body(summary)
                .ts(view.transferredAt() != null ? view.transferredAt() : LocalDateTime.now())
                .ownerAgentId(view.ownerAgentId())
                .ownerAgentName(view.ownerAgentName())
                .build());
    }

    /** 状态变更（含归档）：senderType=SYSTEM，body 写状态摘要。 */
    private void publishStatus(ContentConversationView view, String summary) {
        if (view == null || view.conversationNo() == null) {
            return;
        }
        eventPublisher.publishEvent(ConversationMessageEvent.builder()
                .conversationNo(view.conversationNo())
                .eventType(ConversationMessageEvent.EventType.STATUS)
                .senderType("SYSTEM")
                .senderName("系统")
                .body(summary)
                .ts(view.updatedAt() != null ? view.updatedAt() : LocalDateTime.now())
                .ownerAgentId(view.ownerAgentId())
                .ownerAgentName(view.ownerAgentName())
                .build());
    }
}
