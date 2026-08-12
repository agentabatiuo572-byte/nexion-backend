package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.ConversationTicketResult;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportTicketDetail;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.context.ApplicationEventPublisher;

@ApplicationService
@RequiredArgsConstructor
public class AppSupportService {
    private static final Set<String> CATEGORIES = Set.of(
            "account", "withdrawal", "deposit", "hardware", "earnings", "genesis", "technical", "other");
    private static final Set<String> TICKET_STATUSES = Set.of(
            "OPEN", "IN_PROGRESS", "PENDING_USER", "RESOLVED", "CLOSED");
    private static final Set<String> CONVERSATION_STATUSES = Set.of("OPEN", "TRANSFERRED", "RESOLVED", "CLOSED");
    private static final Set<String> CONVERSATION_TYPES = Set.of("SUPPORT", "ADVISOR");
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final SupportTicketRepository ticketRepository;
    private final ConversationRepository conversationRepository;
    private final SupportKnowledgeRepository knowledgeRepository;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ProductionSupportPathGuard productionPathGuard;

    public ApiResult<PageResult<SupportTicketView>> tickets(
            Long userId, String status, Long pageNum, Long pageSize) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        String normalizedStatus = normalizeUpper(status);
        if (StringUtils.hasText(normalizedStatus) && !TICKET_STATUSES.contains(normalizedStatus)) {
            return validation("SUPPORT_TICKET_STATUS_UNSUPPORTED");
        }
        SupportTicketQueryRequest query = new SupportTicketQueryRequest(
                null, normalizedStatus, null, null, null, userId, null,
                bounded(pageNum, 1, 100000, 1), bounded(pageSize, 1, 100, 50));
        return ApiResult.ok(ticketRepository.pageTickets(query));
    }

    public ApiResult<SupportTicketDetail> ticket(Long userId, String ticketNo) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        SupportTicketView ticket = ownedTicket(userId, ticketNo);
        if (ticket == null) return hiddenNotFound("SUPPORT_TICKET_NOT_FOUND");
        return ApiResult.ok(new SupportTicketDetail(ticket, ticketRepository.userVisibleMessages(ticket.ticketNo())));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<SupportTicketDetail> createTicket(
            Long userId, String idempotencyKey, CreateTicketRequest request) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        if (!validKey(idempotencyKey)) return idempotencyRequired();
        if (request == null || !validCategory(request.category())
                || !boundedText(request.title(), 1, 160) || !boundedText(request.body(), 1, 2000)) {
            return validation("SUPPORT_TICKET_INPUT_INVALID");
        }
        return idempotent("APP_SUPPORT_TICKET_CREATE:" + userId, idempotencyKey, hash(request), () -> {
            LocalDateTime now = LocalDateTime.now(clock);
            String ticketNo = uniqueNo("TK-APP-", now);
            SupportTicketView created = ticketRepository.createTicket(
                    ticketNo, userId, normalizeLower(request.category()), "NORMAL",
                    request.title().trim(), request.body().trim(), null, "Unassigned", "user:" + userId, now);
            audit("APP_SUPPORT_TICKET_CREATED", "SUPPORT_TICKET", created.ticketNo(), userId,
                    Map.of("category", created.category(), "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(new SupportTicketDetail(created, ticketRepository.messages(created.ticketNo())));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<SupportTicketDetail> replyTicket(
            Long userId, String ticketNo, String idempotencyKey, ReplyRequest request) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        if (!validKey(idempotencyKey)) return idempotencyRequired();
        if (request == null || !boundedText(request.body(), 1, 2000) || !validExpectation(request.expectedStatus(), request.expectedVersion())) {
            return validation("SUPPORT_TICKET_REPLY_INVALID");
        }
        return idempotent("APP_SUPPORT_TICKET_REPLY:" + userId, idempotencyKey, hash(ticketNo, request), () -> {
            SupportTicketView ticket = ownedTicket(userId, ticketNo);
            if (ticket == null) return hiddenNotFound("SUPPORT_TICKET_NOT_FOUND");
            if (!matches(ticket.status(), ticket.version(), request.expectedStatus(), request.expectedVersion())) return conflict();
            if ("CLOSED".equals(normalizeUpper(ticket.status())) || Boolean.TRUE.equals(ticket.archived())) {
                return invalidState();
            }
            if (!ticketRepository.appendUserReplyCas(ticket, request.body().trim(), LocalDateTime.now(clock))) return conflict();
            audit("APP_SUPPORT_TICKET_REPLIED", "SUPPORT_TICKET", ticket.ticketNo(), userId,
                    Map.of("bodyLength", request.body().trim().length(), "idempotencyKey", idempotencyKey.trim()));
            return ticket(userId, ticket.ticketNo());
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<SupportTicketDetail> closeTicket(
            Long userId, String ticketNo, String idempotencyKey, CloseRequest request) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        if (!validKey(idempotencyKey)) return idempotencyRequired();
        if (request == null || !validExpectation(request.expectedStatus(), request.expectedVersion())) {
            return validation("SUPPORT_TICKET_CLOSE_INVALID");
        }
        return idempotent("APP_SUPPORT_TICKET_CLOSE:" + userId, idempotencyKey, hash(ticketNo, request), () -> {
            SupportTicketView ticket = ownedTicket(userId, ticketNo);
            if (ticket == null) return hiddenNotFound("SUPPORT_TICKET_NOT_FOUND");
            if (!matches(ticket.status(), ticket.version(), request.expectedStatus(), request.expectedVersion())) return conflict();
            if ("CLOSED".equals(normalizeUpper(ticket.status())) || Boolean.TRUE.equals(ticket.archived())) return invalidState();
            LocalDateTime now = LocalDateTime.now(clock);
            if (!ticketRepository.updateStatusCas(ticket, "CLOSED", "user:" + userId, now)) return conflict();
            audit("APP_SUPPORT_TICKET_CLOSED", "SUPPORT_TICKET", ticket.ticketNo(), userId,
                    Map.of("from", ticket.status(), "idempotencyKey", idempotencyKey.trim()));
            return ticket(userId, ticket.ticketNo());
        });
    }

    public ApiResult<PageResult<ContentConversationView>> conversations(
            Long userId, String status, Long pageNum, Long pageSize) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        String normalizedStatus = normalizeUpper(status);
        if (StringUtils.hasText(normalizedStatus) && !CONVERSATION_STATUSES.contains(normalizedStatus)) {
            return validation("CONVERSATION_STATUS_UNSUPPORTED");
        }
        ConversationQueryRequest query = new ConversationQueryRequest(
                normalizedStatus, null, null, userId, null, false,
                bounded(pageNum, 1, 100000, 1), bounded(pageSize, 1, 100, 50));
        PageResult<ContentConversationView> page = conversationRepository.pageConversations(query);
        return ApiResult.ok(new PageResult<>(page.getTotal(), page.getPageNum(), page.getPageSize(),
                page.getRecords().stream().map(row -> appConversation(
                        row, conversationRepository.userVisibleMessages(row.conversationNo()))).toList()));
    }

    public ApiResult<ContentConversationDetail> conversation(Long userId, String conversationNo) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        ContentConversationView conversation = ownedConversation(userId, conversationNo);
        if (conversation == null) return hiddenNotFound("CONVERSATION_NOT_FOUND");
        List<ContentConversationMessageView> messages = conversationRepository.userVisibleMessages(conversation.conversationNo());
        return ApiResult.ok(new ContentConversationDetail(appConversation(conversation, messages), messages, null));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationDetail> markConversationRead(
            Long userId, String conversationNo, Long lastSeenMessageId, String expectedStatus, Long expectedVersion) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        ContentConversationView conversation = conversationRepository
                .findByConversationNoForUpdate(safeNo(conversationNo)).orElse(null);
        if (conversation == null || !userId.equals(conversation.userId())) return hiddenNotFound("CONVERSATION_NOT_FOUND");
        if (!validExpectation(expectedStatus, expectedVersion)) return validation("CONVERSATION_READ_EXPECTATION_INVALID");
        if (!matches(conversation.status(), conversation.version(), expectedStatus, expectedVersion)) return conversationConflict();
        if ("CLOSED".equals(normalizeUpper(conversation.status()))) return invalidConversationState();
        if (lastSeenMessageId == null || lastSeenMessageId <= 0) return validation("LAST_SEEN_MESSAGE_ID_REQUIRED");
        List<ContentConversationMessageView> messages = conversationRepository.userVisibleMessages(conversation.conversationNo());
        boolean targetVisibleAgentMessage = messages.stream().anyMatch(message -> lastSeenMessageId.equals(message.id())
                && "agent".equalsIgnoreCase(message.senderType()));
        if (!targetVisibleAgentMessage) return hiddenNotFound("CONVERSATION_AGENT_MESSAGE_NOT_FOUND");
        boolean changed = conversationRepository.markAgentMessagesReadThrough(
                conversation.conversationNo(), lastSeenMessageId, "user:" + userId, LocalDateTime.now(clock),
                expectedStatus, expectedVersion);
        ApiResult<ContentConversationDetail> result = conversation(userId, conversation.conversationNo());
        if (changed) {
            publishAfterCommit(ConversationMessageEvent.builder()
                    .conversationNo(conversation.conversationNo()).messageId(lastSeenMessageId)
                    .eventType(ConversationMessageEvent.EventType.RECEIPT).senderType("USER")
                    .senderName("user:" + userId).body("read").ts(LocalDateTime.now(clock)).build());
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationDetail> startConversation(
            Long userId, String idempotencyKey, StartConversationRequest request) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        if (!validKey(idempotencyKey)) return idempotencyRequired();
        if (request == null || !CONVERSATION_TYPES.contains(normalizeUpper(request.conversationType()))
                || !boundedText(request.openingText(), 1, 2000)) {
            return validation("CONVERSATION_INPUT_INVALID");
        }
        return idempotent("APP_CONVERSATION_CREATE:" + userId, idempotencyKey, hash(request), () -> {
            LocalDateTime now = LocalDateTime.now(clock);
            ContentConversationView created = conversationRepository.createUserConversation(
                    uniqueNo("CV-APP-", now), userId, normalizeLower(request.conversationType()), request.openingText().trim(), now);
            audit("APP_CONVERSATION_CREATED", "CONVERSATION", created.conversationNo(), userId,
                    Map.of("type", created.conversationType(), "idempotencyKey", idempotencyKey.trim()));
            ApiResult<ContentConversationDetail> result = conversation(userId, created.conversationNo());
            publish(result.getData(), ConversationMessageEvent.EventType.INITIATE, request.openingText().trim());
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ContentConversationDetail> replyConversation(
            Long userId, String conversationNo, String idempotencyKey, ReplyRequest request) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        if (!validKey(idempotencyKey)) return idempotencyRequired();
        if (request == null || !boundedText(request.body(), 1, 2000) || !validExpectation(request.expectedStatus(), request.expectedVersion())) {
            return validation("CONVERSATION_REPLY_INVALID");
        }
        return idempotent("APP_CONVERSATION_REPLY:" + userId, idempotencyKey, hash(conversationNo, request), () -> {
            ContentConversationView conversation = ownedConversation(userId, conversationNo);
            if (conversation == null) return hiddenNotFound("CONVERSATION_NOT_FOUND");
            if (!matches(conversation.status(), conversation.version(), request.expectedStatus(), request.expectedVersion())) return conversationConflict();
            if (!Set.of("OPEN", "RESOLVED").contains(normalizeUpper(conversation.status()))) return invalidConversationState();
            if (!conversationRepository.replyAsUser(conversation, userId, request.body().trim(), LocalDateTime.now(clock))) {
                return conversationConflict();
            }
            audit("APP_CONVERSATION_REPLIED", "CONVERSATION", conversation.conversationNo(), userId,
                    Map.of("bodyLength", request.body().trim().length(), "idempotencyKey", idempotencyKey.trim()));
            ApiResult<ContentConversationDetail> result = conversation(userId, conversation.conversationNo());
            publish(result.getData(), ConversationMessageEvent.EventType.MESSAGE, request.body().trim());
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ConversationTicketResult> convertConversationToTicket(
            Long userId, String conversationNo, String idempotencyKey, ConvertToTicketRequest request) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        if (!validKey(idempotencyKey)) return idempotencyRequired();
        if (request == null || !validCategory(request.category()) || !boundedText(request.title(), 1, 160)
                || !validExpectation(request.expectedStatus(), request.expectedVersion())) {
            return validation("CONVERSATION_TICKET_INPUT_INVALID");
        }
        return idempotent("APP_CONVERSATION_TO_TICKET:" + userId, idempotencyKey, hash(conversationNo, request), () -> {
            ContentConversationView conversation = conversationRepository
                    .findByConversationNoForUpdate(safeNo(conversationNo)).orElse(null);
            if (conversation == null || !userId.equals(conversation.userId())) return hiddenNotFound("CONVERSATION_NOT_FOUND");
            if (!matches(conversation.status(), conversation.version(), request.expectedStatus(), request.expectedVersion())
                    || "CLOSED".equals(normalizeUpper(conversation.status()))) return conversationConflict();
            LocalDateTime now = LocalDateTime.now(clock);
            String ticketNo = uniqueNo("TK-APP-", now);
            if (!conversationRepository.markConvertedToTicket(conversation, ticketNo, "user:" + userId, now)) {
                return conversationConflict();
            }
            String transcript = conversationRepository.userVisibleMessages(conversation.conversationNo()).stream()
                    .map(message -> message.senderName() + ": " + message.content())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse(conversation.lastMessage());
            SupportTicketView ticket = ticketRepository.createTicket(
                    ticketNo, userId, normalizeLower(request.category()), "NORMAL", request.title().trim(),
                    StringUtils.hasText(transcript) ? transcript : "Conversation " + conversation.conversationNo(),
                    null, "Unassigned", "user:" + userId, now);
            ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
            audit("APP_CONVERSATION_CONVERTED_TO_TICKET", "CONVERSATION", conversation.conversationNo(), userId,
                    Map.of("ticketNo", ticketNo, "idempotencyKey", idempotencyKey.trim()));
            publishAfterCommit(ConversationMessageEvent.builder()
                    .conversationNo(updated.conversationNo()).eventType(ConversationMessageEvent.EventType.STATUS)
                    .senderType("SYSTEM").senderName("System").body("CONVERTED_TO_TICKET")
                    .ts(now).ownerAgentId(updated.ownerAgentId()).ownerAgentName(updated.ownerAgentName()).build());
            return ApiResult.ok(new ConversationTicketResult(
                    appConversation(updated, conversationRepository.userVisibleMessages(updated.conversationNo())),
                    new SupportTicketDetail(ticket, ticketRepository.userVisibleMessages(ticket.ticketNo()))));
        });
    }

    public ApiResult<List<SupportFaqView>> faqs(Long userId, String language, String category) {
        productionPathGuard.requireAllowed(userId);
        if (!validUser(userId)) return forbidden();
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedCategory = normalizeLower(category);
        List<SupportFaqView> published = knowledgeRepository.listFaqs().stream()
                .filter(faq -> "PUBLISHED".equals(normalizeUpper(faq.status())))
                .filter(faq -> "Help Center".equals(faq.surface()))
                .filter(faq -> !StringUtils.hasText(normalizedCategory) || normalizedCategory.equals(normalizeLower(faq.category())))
                .sorted(java.util.Comparator.comparing(SupportFaqView::sortOrder).thenComparing(SupportFaqView::id))
                .toList();
        List<SupportFaqView> localized = published.stream()
                .filter(faq -> normalizedLanguage.equalsIgnoreCase(faq.language()))
                .toList();
        // Support knowledge has an explicit default-language contract. A user
        // must not see an empty Help Center merely because the currently
        // selected locale has not been translated yet; only published App rows
        // are eligible for this fallback.
        if (localized.isEmpty() && !"zh-CN".equalsIgnoreCase(normalizedLanguage)) {
            localized = published.stream()
                    .filter(faq -> "zh-CN".equalsIgnoreCase(faq.language()))
                    .toList();
        }
        return ApiResult.ok(localized);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> idempotent(String scope, String key, String requestHash, Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.execute(scope, key.trim(), requestHash, ApiResult.class, (Supplier) action);
    }

    private SupportTicketView ownedTicket(Long userId, String ticketNo) {
        if (!StringUtils.hasText(ticketNo)) return null;
        return ticketRepository.findByTicketNo(ticketNo.trim()).filter(row -> userId.equals(row.userId())).orElse(null);
    }

    private ContentConversationView ownedConversation(Long userId, String conversationNo) {
        if (!StringUtils.hasText(conversationNo)) return null;
        return conversationRepository.findByConversationNo(conversationNo.trim())
                .filter(row -> userId.equals(row.userId())).orElse(null);
    }

    private ContentConversationView appConversation(
            ContentConversationView row, List<ContentConversationMessageView> messages) {
        int userUnread = (int) messages.stream()
                .filter(message -> "agent".equalsIgnoreCase(message.senderType()))
                .filter(message -> !"read".equalsIgnoreCase(message.receiptStatus()))
                .count();
        return new ContentConversationView(
                row.id(), row.conversationNo(), row.userId(), row.conversationType(), row.status(),
                row.ownerAgentId(), row.ownerAgentName(), userUnread, row.lastMessage(), row.lastMessageAt(),
                row.transferFromAgentId(), row.transferFromAgentName(), row.transferToType(), row.transferToId(),
                row.transferToName(), row.transferReason(), row.transferredAt(), row.updatedAt(), row.version());
    }

    private void publish(ContentConversationDetail detail, ConversationMessageEvent.EventType type, String body) {
        if (detail == null || detail.conversation() == null) return;
        ContentConversationView conversation = detail.conversation();
        var message = detail.messages().stream()
                .filter(row -> "user".equalsIgnoreCase(row.senderType()) && body.equals(row.content()))
                .reduce((left, right) -> right).orElse(null);
        publishAfterCommit(ConversationMessageEvent.builder()
                .conversationNo(conversation.conversationNo()).messageId(message == null ? null : message.id())
                .eventType(type).senderType("USER").senderName("User").body(body)
                .ts(message == null ? LocalDateTime.now(clock) : message.createdAt())
                .ownerAgentId(conversation.ownerAgentId()).ownerAgentName(conversation.ownerAgentName()).build());
    }

    /** SSE is an observation of a durable commit, never a preview of an in-flight command. */
    private void publishAfterCommit(ConversationMessageEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventPublisher.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(event);
            }
        });
    }

    private void audit(String action, String resourceType, String resourceId, Long userId, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId).bizNo(resourceId)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .result("SUCCESS").riskLevel("MEDIUM").detail(detail).build());
    }

    private String uniqueNo(String prefix, LocalDateTime now) {
        return prefix + now.format(NUMBER_TIME) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String normalizeLanguage(String value) {
        if (!StringUtils.hasText(value)) return "en-US";
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "zh", "zh-cn" -> "zh-CN";
            case "vi", "vi-vn" -> "vi-VN";
            default -> "en-US";
        };
    }

    private long bounded(Long value, long min, long max, long fallback) {
        long candidate = value == null ? fallback : value;
        return Math.max(min, Math.min(max, candidate));
    }

    private boolean validUser(Long userId) { return userId != null && userId > 0; }
    private boolean validKey(String value) { return StringUtils.hasText(value) && value.trim().length() <= 128; }
    private boolean validCategory(String value) { return CATEGORIES.contains(normalizeLower(value)); }
    private boolean boundedText(String value, int min, int max) {
        return StringUtils.hasText(value) && value.trim().length() >= min && value.trim().length() <= max;
    }
    private boolean validExpectation(String status, Long version) {
        return StringUtils.hasText(status) && version != null && version >= 0;
    }
    private boolean matches(String currentStatus, Long currentVersion, String expectedStatus, Long expectedVersion) {
        return normalizeUpper(currentStatus).equals(normalizeUpper(expectedStatus))
                && (currentVersion == null ? 0L : currentVersion.longValue()) == expectedVersion.longValue();
    }
    private String normalizeUpper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
    private String normalizeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
    private String safeNo(String value) { return StringUtils.hasText(value) ? value.trim() : ""; }
    private <T> ApiResult<T> forbidden() { return ApiResult.fail(403, "USER_SUBJECT_REQUIRED"); }
    private <T> ApiResult<T> hiddenNotFound(String code) { return ApiResult.fail(404, code); }
    private <T> ApiResult<T> validation(String code) { return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), code); }
    private <T> ApiResult<T> idempotencyRequired() { return ApiResult.fail(400, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name()); }
    private <T> ApiResult<T> conflict() { return ApiResult.fail(409, "SUPPORT_TICKET_CONFLICT"); }
    private <T> ApiResult<T> conversationConflict() { return ApiResult.fail(409, "CONVERSATION_CONFLICT"); }
    private <T> ApiResult<T> invalidState() { return ApiResult.fail(409, "SUPPORT_TICKET_INVALID_STATE"); }
    private <T> ApiResult<T> invalidConversationState() { return ApiResult.fail(409, "CONVERSATION_INVALID_STATE"); }

    public record CreateTicketRequest(String category, String title, String body) {}
    public record ReplyRequest(String body, String expectedStatus, Long expectedVersion) {}
    public record CloseRequest(String expectedStatus, Long expectedVersion) {}
    public record StartConversationRequest(String conversationType, String openingText) {}
    public record ConvertToTicketRequest(String category, String title, String expectedStatus, Long expectedVersion) {}
}
