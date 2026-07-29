package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeOverview;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportSlaBaseline;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.content.dto.SupportFaqStatusRequest;
import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportKnowledgeDeleteRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsSupportKnowledgeService {
    private static final Set<String> FAQ_CATEGORIES = Set.of("general", "account", "withdrawal", "deposit", "kyc", "hardware", "earnings", "genesis", "technical", "other");
    private static final Set<String> SLA_CATEGORIES = SupportSlaBaseline.CATEGORIES;
    private static final Set<String> SURFACES = Set.of("Help Center", "Ticket Create", "Nova");
    private static final Set<String> STATUSES = Set.of("PUBLISHED", "DRAFT");
    private static final Set<String> LANGUAGES = Set.of("zh-CN", "en-US", "vi-VN");
    private static final DateTimeFormatter FAQ_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final SupportKnowledgeRepository knowledgeRepository;
    private final AuditLogService auditLogService;
    private final EventOutboxService eventOutboxService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<SupportKnowledgeOverview> overview() {
        ensureSeedData();
        List<SupportSlaView> slaRules = knowledgeRepository.listSla();
        return ApiResult.ok(new SupportKnowledgeOverview(
                knowledgeRepository.listFaqs(),
                slaRules,
                List.copyOf(FAQ_CATEGORIES),
                List.copyOf(SURFACES),
                List.copyOf(STATUSES),
                slaQueues(slaRules),
                slaEscalations(slaRules),
                List.of("nx_help_article", "nx_support_sla_rule")));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<SupportFaqView> createFaq(String idempotencyKey, SupportFaqUpsertRequest request) {
        ensureSeedData();
        ApiResult<SupportFaqView> guard = requireFaqCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String faqId = "FAQ-" + LocalDateTime.now(clock).format(FAQ_ID_TIME)
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SupportFaqView created = knowledgeRepository.createFaq(faqId, normalizeFaqRequest(request), LocalDateTime.now(clock));
        audit("M4_SUPPORT_FAQ_CREATED", created.id(), request.operator(), Map.of(
                "category", created.category(),
                "surface", created.surface(),
                "status", created.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        publishFaqEvent(created, "create", request.operator(), request.reason());
        return ApiResult.ok(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<SupportFaqView> updateFaq(String faqId, String idempotencyKey, SupportFaqUpsertRequest request) {
        ensureSeedData();
        if (!StringUtils.hasText(faqId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "FAQ_ID_REQUIRED");
        }
        ApiResult<SupportFaqView> guard = requireFaqCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        SupportFaqView current = findFaq(faqId);
        if (current == null) {
            return ApiResult.fail(404, "SUPPORT_FAQ_NOT_FOUND");
        }
        if (!validFaqExpectation(request.expectedStatus(), request.expectedVersion())
                || !current.status().equals(normalizeStatus(request.expectedStatus()))
                || !current.version().equals(request.expectedVersion())) {
            return ApiResult.fail(409, "SUPPORT_FAQ_CONCURRENT_MODIFICATION");
        }
        if (!knowledgeRepository.updateFaqCas(
                current.id(), normalizeFaqRequest(request), request.expectedStatus(), request.expectedVersion(), LocalDateTime.now(clock))) {
            return ApiResult.fail(409, "SUPPORT_FAQ_CONCURRENT_MODIFICATION");
        }
        SupportFaqView updated = findFaq(current.id());
        audit("M4_SUPPORT_FAQ_UPDATED", current.id(), request.operator(), Map.of(
                "fromStatus", current.status(),
                "toStatus", normalizeStatus(request.status()),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        publishFaqEvent(updated, "update", request.operator(), request.reason());
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<SupportFaqView> updateFaqStatus(String faqId, String idempotencyKey, SupportFaqStatusRequest request) {
        ensureSeedData();
        if (!StringUtils.hasText(faqId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "FAQ_ID_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !STATUSES.contains(normalizeStatus(request.status()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_STATUS_UNSUPPORTED");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        SupportFaqView current = findFaq(faqId);
        if (current == null) {
            return ApiResult.fail(404, "SUPPORT_FAQ_NOT_FOUND");
        }
        if (!validFaqExpectation(request.expectedStatus(), request.expectedVersion())
                || !current.status().equals(normalizeStatus(request.expectedStatus()))
                || !current.version().equals(request.expectedVersion())) {
            return ApiResult.fail(409, "SUPPORT_FAQ_CONCURRENT_MODIFICATION");
        }
        String target = normalizeStatus(request.status());
        if (target.equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!knowledgeRepository.updateFaqStatusCas(
                current.id(), target, request.expectedStatus(), request.expectedVersion(), LocalDateTime.now(clock))) {
            return ApiResult.fail(409, "SUPPORT_FAQ_CONCURRENT_MODIFICATION");
        }
        SupportFaqView updated = findFaq(current.id());
        audit("M4_SUPPORT_FAQ_STATUS_CHANGED", current.id(), request.operator(), Map.of(
                "from", current.status(),
                "to", target,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        publishFaqEvent(updated, target.equals("PUBLISHED") ? "publish" : "unpublish", request.operator(), request.reason());
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Void> deleteFaq(String faqId, String idempotencyKey, SupportKnowledgeDeleteRequest request) {
        ensureSeedData();
        if (!StringUtils.hasText(faqId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "FAQ_ID_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        SupportFaqView current = findFaq(faqId);
        if (current == null) {
            return ApiResult.fail(404, "SUPPORT_FAQ_NOT_FOUND");
        }
        if (!validFaqExpectation(request.expectedStatus(), request.expectedVersion())
                || !current.status().equals(normalizeStatus(request.expectedStatus()))
                || !current.version().equals(request.expectedVersion())) {
            return ApiResult.fail(409, "SUPPORT_FAQ_CONCURRENT_MODIFICATION");
        }
        if (!knowledgeRepository.deleteFaqCas(
                current.id(), request.expectedStatus(), request.expectedVersion(), LocalDateTime.now(clock))) {
            return ApiResult.fail(409, "SUPPORT_FAQ_CONCURRENT_MODIFICATION");
        }
        audit("M4_SUPPORT_FAQ_DELETED", current.id(), request.operator(), Map.of(
                "category", current.category(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        publishFaqEvent(new SupportFaqView(
                current.id(), current.category(), current.question(), current.answer(), current.status(),
                current.surface(), current.language(), current.sortOrder(), current.version() + 1, LocalDateTime.now(clock)),
                "delete", request.operator(), request.reason());
        return ApiResult.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<SupportSlaView> updateSla(String category, String idempotencyKey, SupportSlaUpdateRequest request) {
        ensureSeedData();
        String normalizedCategory = normalizeCategory(category);
        if (!SLA_CATEGORIES.contains(normalizedCategory)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_SLA_CATEGORY_UNSUPPORTED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || request.firstResponseMins() == null || request.resolutionHours() == null
                || request.firstResponseMins() <= 0 || request.resolutionHours() <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_SLA_VALUE_INVALID");
        }
        if (!StringUtils.hasText(request.queue()) || !StringUtils.hasText(request.escalation())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_SLA_QUEUE_REQUIRED");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        SupportSlaView current = knowledgeRepository.listSla().stream()
                .filter(row -> normalizedCategory.equals(row.category()))
                .findFirst()
                .orElse(null);
        if (current == null || request.expectedVersion() == null || request.expectedVersion() < 1
                || !request.expectedVersion().equals(current.version())) {
            return ApiResult.fail(409, "SUPPORT_SLA_CONCURRENT_MODIFICATION");
        }
        if (!knowledgeRepository.updateSlaCas(normalizedCategory, request, request.expectedVersion(), LocalDateTime.now(clock))) {
            return ApiResult.fail(409, "SUPPORT_SLA_CONCURRENT_MODIFICATION");
        }
        SupportSlaView updated = knowledgeRepository.listSla().stream()
                .filter(row -> normalizedCategory.equals(row.category()))
                .findFirst()
                .orElse(new SupportSlaView(
                        normalizedCategory,
                        request.firstResponseMins(),
                        request.resolutionHours(),
                        request.queue().trim(),
                        request.escalation().trim(),
                        request.expectedVersion() + 1,
                        LocalDateTime.now(clock)));
        audit("M4_SUPPORT_SLA_CHANGED", normalizedCategory, request.operator(), Map.of(
                "firstResponseMins", updated.firstResponseMins(),
                "resolutionHours", updated.resolutionHours(),
                "queue", updated.queue(),
                "escalation", updated.escalation(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    private void ensureSeedData() {
    }

    private List<String> slaQueues(List<SupportSlaView> slaRules) {
        if (slaRules == null) {
            return List.of();
        }
        return slaRules.stream()
                .map(SupportSlaView::queue)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> slaEscalations(List<SupportSlaView> slaRules) {
        if (slaRules == null) {
            return List.of();
        }
        return slaRules.stream()
                .map(SupportSlaView::escalation)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private ApiResult<SupportFaqView> requireFaqCommand(String idempotencyKey, SupportFaqUpsertRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.question()) || !StringUtils.hasText(request.answer())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_TEXT_REQUIRED");
        }
        if (request.question().trim().length() > 160 || request.answer().trim().length() > 4000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_TEXT_TOO_LONG");
        }
        if (!FAQ_CATEGORIES.contains(normalizeCategory(request.category()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_CATEGORY_UNSUPPORTED");
        }
        if (!SURFACES.contains(normalizeSurface(request.surface()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_SURFACE_UNSUPPORTED");
        }
        if (!STATUSES.contains(normalizeStatus(request.status()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_STATUS_UNSUPPORTED");
        }
        if (!LANGUAGES.contains(normalizeLanguage(request.language()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_LANGUAGE_UNSUPPORTED");
        }
        if (request.sortOrder() == null || request.sortOrder() < 0 || request.sortOrder() > 999999) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_FAQ_SORT_ORDER_INVALID");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private SupportFaqView findFaq(String faqId) {
        return knowledgeRepository.findFaq(faqId.trim()).orElse(null);
    }

    private SupportFaqUpsertRequest normalizeFaqRequest(SupportFaqUpsertRequest request) {
        return new SupportFaqUpsertRequest(
                normalizeCategory(request.category()),
                normalizeSurface(request.surface()),
                request.question().trim(),
                request.answer().trim(),
                normalizeStatus(request.status()),
                normalizeLanguage(request.language()),
                request.sortOrder(),
                request.expectedStatus(),
                request.expectedVersion(),
                operator(request.operator()),
                request.reason().trim());
    }

    private String normalizeCategory(String category) {
        return StringUtils.hasText(category) ? category.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeSurface(String surface) {
        if (!StringUtils.hasText(surface)) {
            return "";
        }
        String trimmed = surface.trim();
        return SURFACES.stream()
                .filter(item -> item.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(trimmed);
    }

    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return "zh-CN";
        }
        String trimmed = language.trim();
        return LANGUAGES.stream().filter(item -> item.equalsIgnoreCase(trimmed)).findFirst().orElse(trimmed);
    }

    private String operator(String operator) {
        return AdminActorResolver.resolve(StringUtils.hasText(operator) ? operator.trim() : "system");
    }

    private boolean validReason(String reason) {
        return StringUtils.hasText(reason)
                && reason.trim().length() >= 8
                && reason.trim().length() <= 200;
    }

    private boolean validFaqExpectation(String expectedStatus, Integer expectedVersion) {
        return STATUSES.contains(normalizeStatus(expectedStatus))
                && expectedVersion != null
                && expectedVersion >= 1;
    }

    private void publishFaqEvent(SupportFaqView faq, String action, String actor, String reason) {
        eventOutboxService.publish("SUPPORT_FAQ", faq.id(), "admin.support_faq_updated", Map.of(
                "faq_id", faq.id(),
                "action", action,
                "category", faq.category(),
                "status", faq.status(),
                "version", faq.version(),
                "language", faq.language(),
                "surface", faq.surface(),
                "operator", operator(actor),
                "reason", reason.trim()));
    }

    private void audit(String action, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("SUPPORT_KNOWLEDGE")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("LOW")
                .detail(detail)
                .build());
    }
}
