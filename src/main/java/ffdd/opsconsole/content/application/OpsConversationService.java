package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AdvisorRoutingDecision;
import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationCustomerProfile;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.domain.ConversationTicketResult;
import ffdd.opsconsole.content.domain.SupportTicketDetail;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationArchiveBatchRequest;
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
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsConversationService {
    private static final Set<String> TRANSFER_TARGET_TYPES = Set.of("agent", "queue", "standby");
    private static final Set<String> CONVERSATION_TYPES = Set.of("advisor", "support");
    private static final Set<String> DIRECT_STATUS_TARGETS = Set.of("OPEN", "RESOLVED", "CLOSED");
    private static final Set<String> TICKET_CATEGORIES = Set.of("account", "withdrawal", "deposit", "hardware", "earnings", "genesis", "technical", "other");
    private static final Set<String> TICKET_PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final DateTimeFormatter CONVERSATION_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter TICKET_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String TIMEOUT_FALLBACK_CONFIG_KEY = "I.session.workbench.timeoutFallback";
    private static final int TRANSFER_TIMEOUT_MINUTES = 30;
    private static final int AUTO_FALLBACK_BATCH_SIZE = 50;
    private static final int REASON_MIN_LENGTH = 8;
    private static final int REASON_MAX_LENGTH = 200;

    /** Escapes the transactional proxy so a partially claimed batch is rolled back before becoming HTTP 409. */
    public static final class ConversationStateConflictException extends RuntimeException {
        public ConversationStateConflictException() {
            super(OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
    }

    private final ConversationRepository conversationRepository;
    private final SupportTicketRepository ticketRepository;
    private final OpsSupportAgentService supportAgentService;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    // 跨域聚合客户档案(只读辅助):复用 user360 同款 service,不另造轮子。
    private final OpsUserService userService;
    private final OpsFinanceService financeService;
    private final OpsDeviceService deviceService;
    private final OpsRiskService riskService;
    // 客户档案标注(自定义标签 + 内部备注,按 user_id 聚合,独立于会话生命周期)
    private final CustomerProfileRepository customerProfileRepository;
    private final ProductionSupportPathGuard productionPathGuard;
    private final SupportOwnershipService ownership;
    private final SupportHumanMessageService humanMessages;
    private final SupportReplyService replies;
    private final SupportCustomerProfileService customerProfiles;

    public ApiResult<Map<String, Object>> overview() {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        Long actor=ownership.actorId(),scope=null;
        if(!ownership.supervisor(actor)){ownership.requireEligibleAgent();scope=actor;}
        Map<String, Object> response = new LinkedHashMap<>(conversationRepository.counters(scope));
        response.put("domain", "I9");
        response.put("statuses", List.of("OPEN", "TRANSFERRED", "RESOLVED", "CLOSED"));
        response.put("conversationTypes", List.of("advisor", "support", "ai"));
        response.put("pendingMeaning", "unread or transferred conversations not archived");
        response.put("transferStateMachine", List.of("OPEN->TRANSFERRED", "TRANSFERRED->TRANSFERRED(wait)", "TRANSFERRED->OPEN", "OPEN->RESOLVED", "RESOLVED->CLOSED"));
        response.put("sources", List.of("nx_conversation", "nx_conversation_transfer"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<ContentConversationView>> conversations(ConversationQueryRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        Long actor=ownership.actorId();
        if (!ownership.supervisor(actor)) {
            ownership.requireEligibleAgent();
            if (request == null) request=new ConversationQueryRequest(null,null,null,null,null,null,1L,20L);
            request=new ConversationQueryRequest(request.status(),request.type(),String.valueOf(actor),request.userId(),request.keyword(),request.unreadOnly(),request.pageNum(),request.pageSize(),request.archived());
        }
        return ApiResult.ok(conversationRepository.pageConversations(request));
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ApiResult<ContentConversationDetail> detail(String conversationNo) {
        ownership.readConversation(conversationNo);
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        List<ContentConversationMessageView> messages = conversationRepository.messages(conversation.conversationNo());
        // 跨域聚合客户档案(只读辅助);内部对每个子域 try/catch 降级,绝不抛异常中断会话详情。
        ConversationCustomerProfile customerProfile = buildCustomerProfile(conversation);
        return ApiResult.ok(new ContentConversationDetail(conversation, messages, customerProfile));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Void> markReadReceipt(String conversationNo, Long lastSeenMessageId, Long authenticatedUserId) {
        productionPathGuard.requireOpsWriteAllowed();
        String normalized = conversationNo == null ? "" : conversationNo.trim();
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if(authenticatedUserId==null) return ApiResult.fail(403,"CONVERSATION_USER_MISMATCH");
        ownership.lockCustomerConversation(authenticatedUserId,normalized);
        ContentConversationView conversation = conversationRepository.findByConversationNoForUpdate(normalized).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if (authenticatedUserId == null || !authenticatedUserId.equals(conversation.userId())) {
            return ApiResult.fail(403, "CONVERSATION_USER_MISMATCH");
        }
        if (lastSeenMessageId == null || lastSeenMessageId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LAST_SEEN_MESSAGE_ID_REQUIRED");
        }
        boolean targetIsAgentMessage = conversationRepository.messages(normalized).stream()
                .anyMatch(message -> lastSeenMessageId.equals(message.id())
                        && "agent".equalsIgnoreCase(message.senderType()));
        if (!targetIsAgentMessage) {
            return ApiResult.fail(404, "CONVERSATION_AGENT_MESSAGE_NOT_FOUND");
        }
        boolean updated = conversationRepository.markAgentMessagesReadThrough(
                normalized,
                lastSeenMessageId,
                "user:" + authenticatedUserId,
                LocalDateTime.now(clock),
                conversation.status(),
                conversation.version());
        if (!updated) {
            return ApiResult.fail(404, "CONVERSATION_AGENT_MESSAGE_NOT_FOUND");
        }
        return ApiResult.ok();
    }

    /** Shared service facts; unknown and failed groups stay explicit rather than becoming zero. */
    private ConversationCustomerProfile buildCustomerProfile(ContentConversationView conversation) {
        Long customer=conversation.userId();
        return customer==null || customer<1?null:customerProfiles.conversationProfile(customer);
    }


    public ApiResult<List<Map<String, Object>>> transferTargets() {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        return ApiResult.ok(supportAgentService.transferTargets());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> transfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferRequest request) {
        ownership.readConversation(conversationNo);
        return ApiResult.fail(409, "SUPPORT_FORMAL_TRANSFER_REQUIRED");
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> acceptTransfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        ownership.readConversation(conversationNo);
        return ApiResult.fail(409, "SUPPORT_FORMAL_TRANSFER_REQUIRED");
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> returnTransfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        ownership.readConversation(conversationNo);
        return ApiResult.fail(409, "SUPPORT_FORMAL_TRANSFER_REQUIRED");
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> waitTransfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        ownership.readConversation(conversationNo);
        return ApiResult.fail(409, "SUPPORT_FORMAL_TRANSFER_REQUIRED");
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> reply(
            String conversationNo,
            String idempotencyKey,
            ConversationReplyRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        return replyWithMessageId(conversationNo, idempotencyKey, request).result();
    }

    @Transactional(rollbackFor = Exception.class)
    public MessageCommandResult replyWithMessageId(
            String conversationNo,
            String idempotencyKey,
            ConversationReplyRequest request) {
        return replyWithMessageId(null,conversationNo,idempotencyKey,request);
    }

    @Transactional(rollbackFor = Exception.class)
    public MessageCommandResult replyWithMessageIdForActor(Long actorId,String conversationNo,String idempotencyKey,ConversationReplyRequest request) {
        SupportAttachmentService.positiveId(actorId);
        if(request!=null && (request.replyTargets()==null || !request.replyTargets().isEmpty() || request.replyThroughMessageId()!=null))
            throw new ffdd.opsconsole.shared.exception.BizException(422,"SUPPORT_REPLY_TARGETS_INVALID");
        return replyWithMessageId(actorId,conversationNo,idempotencyKey,request);
    }

    private MessageCommandResult replyWithMessageId(Long persistedActor,String conversationNo,String idempotencyKey,ConversationReplyRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireReplyCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return new MessageCommandResult(guard, null);
        }
        if(persistedActor==null) ownership.writeConversation(conversationNo,true);
        else ownership.requireWriterForActor(persistedActor,ownership.conversationCustomer(conversationNo),true);
        ContentConversationView conversation = conversationRepository.findByConversationNoForUpdate(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return new MessageCommandResult(ApiResult.fail(404, "CONVERSATION_NOT_FOUND"), null);
        }
        var prepared=persistedActor==null ? humanMessages.prepare(conversation.userId(),"ADMIN",ownership.actorId(),idempotencyKey,
                "REPLY:"+conversationNo,request,request.clientMessageId(),request.kind(),request.intent(),
                request.attachmentId(),request.expectedAssignmentId())
            : humanMessages.prepareForActor(persistedActor,conversation.userId(),idempotencyKey,
                "REPLY:"+conversationNo,request,request.clientMessageId(),request.kind(),request.intent(),
                request.attachmentId(),request.expectedAssignmentId());
        if(prepared.previousMessageId()!=null) return new MessageCommandResult(ApiResult.ok(conversation),prepared.previousMessageId());
        if (!matchesExpectedSnapshot(request.expectedStatus(), request.expectedVersion(), conversation)
                || "TRANSFERRED".equalsIgnoreCase(conversation.status()) || "CLOSED".equalsIgnoreCase(conversation.status())
                || Boolean.TRUE.equals(conversation.archived())) {
            return new MessageCommandResult(invalidState(), null);
        }
        String body = prepared.content(request.body());
        String actor = persistedActor==null ? operator(request.operator()) : "admin:"+persistedActor;
        LocalDateTime now = LocalDateTime.now(clock);
        Long messageId = persistedActor==null ? conversationRepository.replyAndReturnMessageId(conversation,body,actor,now)
            : conversationRepository.replyAndReturnMessageId(conversation,body,persistedActor,actor,now);
        if (messageId == null) {
            return new MessageCommandResult(invalidState(), null);
        }
        if(persistedActor==null) humanMessages.committed(prepared,messageId,idempotencyKey);
        else humanMessages.committedForActor(prepared,messageId,idempotencyKey);
        replies.handled(conversation.userId(),conversation.conversationNo(),messageId,request);
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        Map<String,Object> detail=Map.of(
                "bodyLength", body.length(),
                "reason", reasonOrDefault(request.reason(), "agent reply"),
                "idempotencyKey", idempotencyKey.trim());
        if(persistedActor==null) audit("I9_CONVERSATION_REPLIED",conversation.conversationNo(),actor,detail);
        else auditForActor("I9_CONVERSATION_REPLIED",conversation.conversationNo(),persistedActor,detail);
        return new MessageCommandResult(ApiResult.ok(updated), messageId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<List<String>> addCustomTag(String conversationNo, String idempotencyKey, CustomerTagRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ownership.writeConversation(conversationNo,true);
        if(!SupportOwnershipService.hasAuthority("service_m3_write")) throw new ffdd.opsconsole.shared.exception.BizException(403,"SUPPORT_EDIT_FORBIDDEN");
        ensureSeedData();
        ApiResult<List<String>> guard = requireProfileTagCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        Long userId = requireUserId(conversationNo);
        if (userId == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_LINKED_TO_USER");
        }
        String tag = request.tag().trim();
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            customerProfileRepository.addCustomTag(userId, tag, actor, now);
        } catch (DuplicateKeyException ignored) {
            // 幂等:标签已存在(并发重复点击 / 删后重加),视为成功
        }
        audit("I9_CUSTOMER_TAG_ADDED", "U-" + userId, actor, Map.of(
                "userId", userId,
                "conversationNo", conversationNo,
                "tag", tag,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(customerProfileRepository.findCustomTags(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<List<String>> removeCustomTag(String conversationNo, String idempotencyKey, CustomerTagRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ownership.writeConversation(conversationNo,true);
        if(!SupportOwnershipService.hasAuthority("service_m3_write")) throw new ffdd.opsconsole.shared.exception.BizException(403,"SUPPORT_EDIT_FORBIDDEN");
        ensureSeedData();
        ApiResult<List<String>> guard = requireProfileTagCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        Long userId = requireUserId(conversationNo);
        if (userId == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_LINKED_TO_USER");
        }
        String tag = request.tag().trim();
        String actor = operator(request.operator());
        if (!customerProfileRepository.removeCustomTag(userId, tag)) {
            return ApiResult.fail(404, "CUSTOMER_TAG_NOT_FOUND");
        }
        audit("I9_CUSTOMER_TAG_REMOVED", "U-" + userId, actor, Map.of(
                "userId", userId,
                "conversationNo", conversationNo,
                "tag", tag,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(customerProfileRepository.findCustomTags(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ConversationCustomerProfile.CustomerNote> addNote(String conversationNo, String idempotencyKey, CustomerNoteRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ownership.writeConversation(conversationNo,true);
        if(!SupportOwnershipService.hasAuthority("service_m3_write")) throw new ffdd.opsconsole.shared.exception.BizException(403,"SUPPORT_EDIT_FORBIDDEN");
        ensureSeedData();
        ApiResult<ConversationCustomerProfile.CustomerNote> guard = requireProfileNoteCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        Long userId = requireUserId(conversationNo);
        if (userId == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_LINKED_TO_USER");
        }
        String text = request.text().trim();
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        ConversationCustomerProfile.CustomerNote created = customerProfileRepository.addNote(userId,ownership.actorId(), actor, text, actor, now);
        audit("I9_CUSTOMER_NOTE_ADDED", "U-" + userId, actor, Map.of(
                "userId", userId,
                "conversationNo", conversationNo,
                "noteId", created.id(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Void> removeNote(String conversationNo, Long noteId, String idempotencyKey, CustomerNoteRemoveRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ownership.writeConversation(conversationNo,true);
        if(!SupportOwnershipService.hasAuthority("service_m3_write")) throw new ffdd.opsconsole.shared.exception.BizException(403,"SUPPORT_EDIT_FORBIDDEN");
        ensureSeedData();
        ApiResult<Void> guard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (noteId == null || noteId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTE_ID_REQUIRED");
        }
        String actor = operator(request.operator());
        Long customerId=requireUserId(conversationNo);
        if (customerProfileRepository.findNotes(customerId).stream().noneMatch(n->String.valueOf(noteId).equals(n.id()))) return ApiResult.fail(404,"CUSTOMER_NOTE_NOT_FOUND");
        if (!customerProfileRepository.removeNote(noteId, actor, LocalDateTime.now(clock))) {
            return ApiResult.fail(404, "CUSTOMER_NOTE_NOT_FOUND");
        }
        audit("I9_CUSTOMER_NOTE_REMOVED", "U-" + noteId, actor, Map.of(
                "noteId", noteId,
                "conversationNo", conversationNo,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> updateStatus(
            String conversationNo,
            String idempotencyKey,
            ConversationStatusRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireStatusCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ownership.writeConversation(conversationNo, true);
        ContentConversationView conversation = conversationRepository.findByConversationNoForUpdate(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        String targetStatus = normalizeStatus(request.status());
        if(Set.of("CLOSED","RESOLVED").contains(targetStatus)) replies.requireHandled(conversationNo);
        if (!matchesExpectedSnapshot(request.expectedStatus(), request.expectedVersion(), conversation)) {
            return invalidState();
        }
        if (!canDirectStatusChange(conversation.status(), targetStatus)) {
            return invalidState();
        }
        String actor = operator(request.operator());
        if (!conversationRepository.updateStatus(conversation, targetStatus, actor, LocalDateTime.now(clock))) {
            return invalidState();
        }
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_STATUS_CHANGED", conversation.conversationNo(), actor, Map.of(
                "from", conversation.status(),
                "to", targetStatus,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> archive(
            String conversationNo,
            String idempotencyKey,
            ConversationArchiveRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ownership.writeConversation(conversationNo, true);
        ContentConversationView conversation = conversationRepository.findByConversationNoForUpdate(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        boolean archived = request == null || request.archived() == null || request.archived();
        if(archived) replies.requireHandled(conversationNo);
        if (!matchesExpectedSnapshot(request.expectedStatus(), request.expectedVersion(), conversation)) {
            return invalidState();
        }
        if (archived && "TRANSFERRED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        if (archived == Boolean.TRUE.equals(conversation.archived())) return ApiResult.ok(conversation);
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        if (!conversationRepository.archive(conversation, archived, actor, now)) {
            return invalidState();
        }
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit(archived ? "I9_CONVERSATION_ARCHIVED" : "I9_CONVERSATION_UNARCHIVED", conversation.conversationNo(), actor, Map.of(
                "status", conversation.status(),
                "fromArchived", conversation.archived(), "toArchived", archived,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<List<ContentConversationView>> archiveBatch(
            String idempotencyKey,
            ConversationArchiveBatchRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || request.conversationNos() == null || request.conversationNos().isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_ARCHIVE_BATCH_EMPTY");
        }
        if (invalidReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        List<String> ids = request.conversationNos().stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (ids.isEmpty() || ids.size() > 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_ARCHIVE_BATCH_SIZE_INVALID");
        }
        ownership.lockWriters(ids.stream().map(ownership::conversationCustomer));
        Map<String, ContentConversationView> lockedRows = new LinkedHashMap<>();
        // Stable order prevents deadlocks between overlapping batch requests.
        for (String id : ids.stream().sorted().toList()) {
            ContentConversationView row = conversationRepository.findByConversationNoForUpdate(id).orElse(null);
            if (row == null) return ApiResult.fail(404, "CONVERSATION_NOT_FOUND:" + id);
            Long expectedVersion = request.expectedVersions() == null ? null : request.expectedVersions().get(id);
            if ("TRANSFERRED".equalsIgnoreCase(row.status()) || Boolean.TRUE.equals(row.archived())
                    || expectedVersion == null
                    || !expectedVersion.equals(row.version())) return invalidBatchState();
            replies.requireHandled(id);
            lockedRows.put(id, row);
        }
        List<ContentConversationView> rows = ids.stream().map(lockedRows::get).toList();
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        List<ContentConversationView> updatedRows = new ArrayList<>();
        for (ContentConversationView row : rows) {
            if (!conversationRepository.archive(row, true, actor, now)) {
                // Must escape the transactional proxy; returning 409 here would commit earlier rows.
                throw new ConversationStateConflictException();
            }
            audit("I9_CONVERSATION_ARCHIVED", row.conversationNo(), actor, Map.of(
                    "status", row.status(), "fromArchived", false, "toArchived", true, "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim(), "batchSize", rows.size()));
            updatedRows.add(conversationRepository.findByConversationNo(row.conversationNo()).orElse(row));
        }
        return ApiResult.ok(updatedRows);
    }

    private ApiResult<List<ContentConversationView>> invalidBatchState() {
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Transactional(rollbackFor = Exception.class)
    public int runTimeoutFallback() {
        return runTimeoutFallbackConversationNos().size();
    }

    /**
     * The scheduler receives these identifiers only after this transactional proxy has committed.
     * Failed or stale compare-and-set attempts are deliberately absent and must not invalidate sockets.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> runTimeoutFallbackConversationNos() {
        productionPathGuard.requireOpsWriteAllowed();
        return List.of(); // Human assignment changes only through the formal customer transfer transaction.
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ApiResult<ConversationTicketResult> convertToTicket(
            String conversationNo,
            String idempotencyKey,
            ConversationTicketRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        ApiResult<ConversationTicketResult> guard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ownership.writeConversation(conversationNo, true);
        ContentConversationView conversation = conversationRepository.findByConversationNoForUpdate(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if (!matchesExpectedSnapshot(request.expectedStatus(), request.expectedVersion(), conversation)
                || "CLOSED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        String category = normalizeTicketCategory(request.category());
        String priority = normalizeTicketPriority(request.priority());
        if (!TICKET_CATEGORIES.contains(category)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_CATEGORY_UNSUPPORTED");
        }
        if (!TICKET_PRIORITIES.contains(priority)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_PRIORITY_UNSUPPORTED");
        }
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        List<ContentConversationMessageView> messages = conversationRepository.messages(conversation.conversationNo());
        String ticketNo = "TK-" + now.format(TICKET_NO_TIME);
        replies.requireHandled(conversation.conversationNo());
        if (!conversationRepository.markConvertedToTicket(conversation, ticketNo, actor, now)) {
            return ApiResult.fail(409, "CONVERSATION_ALREADY_CONVERTED_TO_TICKET");
        }
        SupportTicketView created = ticketRepository.createTicket(
                ticketNo,
                conversation.userId(),
                category,
                priority,
                titleOrDefault(request.title(), conversation),
                transcriptBody(conversation, messages),
                request.assignedAdminId(),
                null, // Responsibility and its display name come from the formal customer binding.
                actor,
                now);
        ticketRepository.markConversationSource(created.ticketNo(),conversation.conversationNo());
        created=ticketRepository.findByTicketNo(created.ticketNo()).orElseThrow();
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        SupportTicketDetail ticketDetail = new SupportTicketDetail(created, ticketRepository.messages(created.ticketNo()));
        audit("I9_CONVERSATION_CONVERTED_TO_TICKET", conversation.conversationNo(), actor, auditDetail(
                "ticketNo", created.ticketNo(),
                "category", category,
                "priority", priority,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(new ConversationTicketResult(updated, ticketDetail));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationView> initiate(
            String idempotencyKey,
            ConversationInitiateRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        return initiateWithMessageId(idempotencyKey, request).result();
    }

    @Transactional(rollbackFor = Exception.class)
    public MessageCommandResult initiateWithMessageId(
            String idempotencyKey,
            ConversationInitiateRequest request) {
        return initiateWithMessageId(null,idempotencyKey,request);
    }

    @Transactional(rollbackFor = Exception.class)
    public MessageCommandResult initiateWithMessageIdForActor(Long actorId,String idempotencyKey,ConversationInitiateRequest request) {
        SupportAttachmentService.positiveId(actorId);
        if(request!=null && (request.replyTargets()==null || !request.replyTargets().isEmpty()))
            throw new ffdd.opsconsole.shared.exception.BizException(422,"SUPPORT_REPLY_TARGETS_INVALID");
        return initiateWithMessageId(actorId,idempotencyKey,request);
    }

    private MessageCommandResult initiateWithMessageId(Long persistedActor,String idempotencyKey,ConversationInitiateRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireInitiateCommand(idempotencyKey, request);
        if (guard != null) {
            return new MessageCommandResult(guard, null);
        }
        if(persistedActor==null) ownership.requireWriter(request.userId(),true);
        else ownership.requireWriterForActor(persistedActor,request.userId(),true);
        var prepared=persistedActor==null ? humanMessages.prepare(request.userId(),"ADMIN",ownership.actorId(),idempotencyKey,
                "CREATE",request,request.clientMessageId(),request.kind(),request.intent(),
                request.attachmentId(),request.expectedAssignmentId())
            : humanMessages.prepareForActor(persistedActor,request.userId(),idempotencyKey,
                "CREATE",request,request.clientMessageId(),request.kind(),request.intent(),
                request.attachmentId(),request.expectedAssignmentId());
        if(prepared.previousMessageId()!=null) return new MessageCommandResult(
                ApiResult.ok(conversationRepository.findByConversationNoForUpdate(prepared.previousConversationNo()).orElseThrow()),prepared.previousMessageId());
        String type = normalizeConversationType(request.conversationType());
        String actor = persistedActor==null ? operator(request.operator()) : "admin:"+persistedActor;
        AdvisorRoutingDecision routing = routingDecision(type, request, actor);
        String ownerName = routing.targetName();
        String ownerId = routing.targetId();
        String text = prepared.content(request.openingText());
        LocalDateTime now = LocalDateTime.now(clock);
        String conversationNo = "CV-OUT-" + now.format(CONVERSATION_NO_TIME);
        ConversationRepository.PersistedConversation persisted = persistedActor==null
            ? conversationRepository.createConversationWithMessage(conversationNo,request.userId(),type,ownerId,ownerName,text,now)
            : conversationRepository.createConversationWithMessage(conversationNo,request.userId(),type,ownerId,ownerName,text,persistedActor,actor,now);
        ContentConversationView created = persisted.conversation();
        if(persistedActor==null) humanMessages.committed(prepared,persisted.messageId(),idempotencyKey);
        else humanMessages.committedForActor(prepared,persisted.messageId(),idempotencyKey);
        if(request.replyTargets()!=null) replies.handled(request.userId(),created.conversationNo(),persisted.messageId(),
                new ConversationReplyRequest(text,created.status(),created.version(),request.reason(),request.operator(),request.replyTargets(),null));
        SupportTicketView fallbackTicket = routing.fallbackTicket()
                ? createAdvisorFallbackTicket(created, text, routing, actor, now)
                : null;
        Map<String, Object> detail = auditDetail(
                "conversationType", type,
                "userId", request.userId() == null ? "audience" : request.userId(),
                "openingLength", text.length(),
                "ownerAgentId", ownerId,
                "ownerAgentName", ownerName,
                "routingTargetType", routing.targetType(),
                "routingReason", routing.reason(),
                "dedicatedAdvisor", routing.dedicated(),
                "fallbackTicket", routing.fallbackTicket(),
                "reason", reasonOrDefault(request.reason(), "single-user routine"),
                "idempotencyKey", idempotencyKey.trim());
        if (fallbackTicket != null) {
            detail.put("fallbackTicketNo", fallbackTicket.ticketNo());
        }
        if(persistedActor==null) audit("I9_CONVERSATION_INITIATED",created.conversationNo(),actor,detail);
        else auditForActor("I9_CONVERSATION_INITIATED",created.conversationNo(),persistedActor,detail);
        return new MessageCommandResult(ApiResult.ok(created), persisted.messageId());
    }

    public record MessageCommandResult(ApiResult<ContentConversationView> result, Long messageId) {}

    private AdvisorRoutingDecision routingDecision(String type, ConversationInitiateRequest request, String actor) {
        return supportAgentService.routeAdvisorForUser(request.userId());
    }

    private SupportTicketView createAdvisorFallbackTicket(
            ContentConversationView conversation,
            String openingText,
            AdvisorRoutingDecision routing,
            String actor,
            LocalDateTime now) {
        String ticketNo = "TK-" + now.format(TICKET_NO_TIME);
        return ticketRepository.createTicket(
                ticketNo,
                conversation.userId(),
                "account",
                "NORMAL",
                "Advisor standby fallback for " + conversation.conversationNo(),
                "Advisor conversation routed to " + routing.targetName() + ".\n" + openingText,
                routing.agentAdminId(),
                routing.targetName(),
                actor,
                now);
    }

    private void ensureSeedData() {
    }

    private ApiResult<ContentConversationView> requireTransferCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || invalidReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.targetId())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TARGET_REQUIRED");
        }
        return null;
    }

    private ApiResult<ContentConversationView> requireDecisionCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || invalidReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<ContentConversationView> requireReplyCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationReplyRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !SupportHumanMessageService.validContent(request.kind(),request.body())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPLY_BODY_REQUIRED");
        }
        if (SupportHumanMessageService.text(request.body()).length() > 2000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPLY_BODY_TOO_LONG");
        }
        if (invalidReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<ContentConversationView> requireStatusCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationStatusRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.status())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "STATUS_REQUIRED");
        }
        if (!DIRECT_STATUS_TARGETS.contains(normalizeStatus(request.status()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "STATUS_UNSUPPORTED");
        }
        if (invalidReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<ContentConversationView> requireInitiateCommand(
            String idempotencyKey,
            ConversationInitiateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !SupportHumanMessageService.validContent(request.kind(),request.openingText())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPENING_TEXT_REQUIRED");
        }
        if (SupportHumanMessageService.text(request.openingText()).length() > 2000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPENING_TEXT_TOO_LONG");
        }
        String type = normalizeConversationType(request.conversationType());
        if (!CONVERSATION_TYPES.contains(type)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_TYPE_UNSUPPORTED");
        }
        if (request.userId() == null && invalidReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private <T> ApiResult<T> requireReasonCommand(String conversationNo, String idempotencyKey, String reason) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (invalidReason(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    /** 标签写命令校验:复用 reason 8..200 + 幂等头 + tag 非空且 ≤64。 */
    private <T> ApiResult<T> requireProfileTagCommand(String conversationNo, String idempotencyKey, CustomerTagRequest request) {
        ApiResult<T> reasonGuard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (reasonGuard != null) {
            return reasonGuard;
        }
        if (request == null || !StringUtils.hasText(request.tag())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CUSTOMER_TAG_REQUIRED");
        }
        if (request.tag().trim().length() > 64) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CUSTOMER_TAG_TOO_LONG");
        }
        return null;
    }

    /** 备注写命令校验:复用 reason 8..200 + 幂等头 + text 非空且 ≤2000。 */
    private <T> ApiResult<T> requireProfileNoteCommand(String conversationNo, String idempotencyKey, CustomerNoteRequest request) {
        ApiResult<T> reasonGuard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (reasonGuard != null) {
            return reasonGuard;
        }
        if (request == null || !StringUtils.hasText(request.text())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CUSTOMER_NOTE_REQUIRED");
        }
        if (request.text().trim().length() > 2000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CUSTOMER_NOTE_TOO_LONG");
        }
        return null;
    }

    /** 解析 conversationNo → userId;会话不存在或未关联用户返回 null(404 由调用方判定)。 */
    private Long requireUserId(String conversationNo) {
        ownership.readConversation(conversationNo);
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return null;
        }
        Long userId = conversation.userId();
        return (userId == null || userId <= 0) ? null : userId;
    }

    private <T> ApiResult<T> invalidState() {
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    private String normalizeTargetType(String targetType) {
        String normalized = StringUtils.hasText(targetType)
                ? targetType.trim().toLowerCase(Locale.ROOT)
                : "agent";
        if (!TRANSFER_TARGET_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported transfer target type");
        }
        return normalized;
    }

    private String normalizeConversationType(String conversationType) {
        return StringUtils.hasText(conversationType) ? conversationType.trim().toLowerCase(Locale.ROOT) : "support";
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private boolean matchesExpectedSnapshot(String expectedStatus, Long expectedVersion, ContentConversationView actual) {
        return StringUtils.hasText(expectedStatus)
                && expectedVersion != null
                && expectedVersion >= 0
                && normalizeStatus(expectedStatus).equals(normalizeStatus(actual.status()))
                && expectedVersion.equals(actual.version());
    }

    private boolean invalidReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return true;
        }
        int length = reason.trim().length();
        return length < REASON_MIN_LENGTH || length > REASON_MAX_LENGTH;
    }

    private String normalizeTicketCategory(String category) {
        return StringUtils.hasText(category) ? category.trim().toLowerCase(Locale.ROOT) : "account";
    }

    private String normalizeTicketPriority(String priority) {
        return StringUtils.hasText(priority) ? priority.trim().toUpperCase(Locale.ROOT) : "NORMAL";
    }

    private boolean canDirectStatusChange(String currentStatus, String targetStatus) {
        String current = normalizeStatus(currentStatus);
        if (current.equals(targetStatus)) return true;
        return switch (current) {
            case "OPEN" -> "RESOLVED".equals(targetStatus);
            case "RESOLVED" -> "OPEN".equals(targetStatus) || "CLOSED".equals(targetStatus);
            default -> false;
        };
    }

    private String statusLabel(String status) {
        return switch (normalizeStatus(status)) {
            case "OPEN" -> "进行中";
            case "TRANSFERRED" -> "转入待处理";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            default -> "未知";
        };
    }

    private String senderLabel(String senderType) {
        return switch (StringUtils.hasText(senderType) ? senderType.trim().toUpperCase(Locale.ROOT) : "") {
            case "USER" -> "用户";
            case "AGENT" -> "坐席";
            default -> "系统";
        };
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String operator(String operator) {
        return AdminActorResolver.resolve(StringUtils.hasText(operator) ? operator.trim() : "system");
    }

    private String reasonOrDefault(String reason, String fallback) {
        return StringUtils.hasText(reason) ? reason.trim() : fallback;
    }

    private String assignedName(String assignedAdminName, String fallback) {
        return StringUtils.hasText(assignedAdminName) ? assignedAdminName.trim() : fallback;
    }

    private boolean timeoutFallbackEnabled() {
        return configFacade.activeValue(TIMEOUT_FALLBACK_CONFIG_KEY)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> Set.of("on", "true", "1", "enabled", "yes").contains(value))
                .isPresent();
    }

    private Optional<Long> parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private String titleOrDefault(String title, ContentConversationView conversation) {
        String value = StringUtils.hasText(title)
                ? title.trim()
                : "会话 " + conversation.conversationNo() + " 后续跟进";
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private String transcriptBody(ContentConversationView conversation, List<ContentConversationMessageView> messages) {
        StringBuilder body = new StringBuilder();
        body.append("会话号: ").append(conversation.conversationNo()).append('\n');
        body.append("用户: ").append(conversation.userId()).append('\n');
        body.append("状态: ").append(statusLabel(conversation.status())).append('\n');
        for (ContentConversationMessageView message : messages) {
            body.append('[')
                    .append(senderLabel(message.senderType()))
                    .append("] ")
                    .append(message.senderName())
                    .append(": ")
                    .append(message.transcriptText())
                    .append('\n');
            if (body.length() >= 1997) {
                return body.substring(0, 1997) + "...";
            }
        }
        if (messages.isEmpty() && StringUtils.hasText(conversation.lastMessage())) {
            body.append("[最后消息] ").append(conversation.lastMessage());
        }
        String result = body.toString().trim();
        return result.length() > 2000 ? result.substring(0, 1997) + "..." : result;
    }

    private Map<String, Object> auditDetail(Object... values) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            detail.put(String.valueOf(values[index]), values[index + 1]);
        }
        return detail;
    }

    private void audit(String action, String conversationNo, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(auditRequest(action,conversationNo,operator(operator),detail));
    }

    private void auditForActor(String action,String conversationNo,Long actor,Map<String,Object> detail) {
        AuditLogWriteRequest request=auditRequest(action,conversationNo,"admin:"+actor,detail);
        request.setActorId(actor);
        auditLogService.recordRequiredForTrustedActor(request);
    }

    private AuditLogWriteRequest auditRequest(String action,String conversationNo,String operator,Map<String,Object> detail) {
        return AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("CONVERSATION")
                .resourceId(conversationNo)
                .bizNo(conversationNo)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build();
    }
}
