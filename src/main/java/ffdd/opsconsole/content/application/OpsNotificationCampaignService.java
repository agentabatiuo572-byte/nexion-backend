package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NotificationCampaignOverview;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository.CampaignDeliveryFacts;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationCampaignStats;
import ffdd.opsconsole.content.domain.NotificationAudienceCatalog;
import ffdd.opsconsole.content.domain.NotificationTranslation;
import ffdd.opsconsole.shared.i18n.InterfaceLanguages;
import ffdd.opsconsole.content.domain.NotificationAudienceEstimateView;
import ffdd.opsconsole.content.domain.NotificationAudienceOption;
import ffdd.opsconsole.content.domain.NotificationAudienceTarget;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
import ffdd.opsconsole.content.domain.NotificationSwipeRouteView;
import ffdd.opsconsole.content.domain.NotificationDeliveryCatalog;
import ffdd.opsconsole.content.dto.NotificationCampaignActionRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import ffdd.opsconsole.content.dto.NotificationAudienceEstimateRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class OpsNotificationCampaignService {
    private static final Set<String> TIERS = Set.of("critical", "high", "normal", "low");
    private static final Set<String> ACTIVE_STATUSES = Set.of("draft", "scheduled", "sending", "sent", "failed", "cancelled");
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final List<String> PHASES = List.of("P1", "P2", "P3", "P4", "P5", "P6");
    private static final Set<String> KINDS = Set.of("system", "commission", "team", "staking", "market", "genesis");
    private static final List<NotificationAudienceOption> CTA_ROUTES = List.of(
            new NotificationAudienceOption("", "无跳转"),
            new NotificationAudienceOption("/pages/me/wallet-repurchase", "我的 · 锁定计划"),
            new NotificationAudienceOption("/pages/team/team", "赚取 · 团队"),
            new NotificationAudienceOption("/pages/market/market", "商城 · 市场"),
            new NotificationAudienceOption("/pages/genesis/marketplace", "商城 · Genesis"));
    private static final List<NotificationSwipeRouteView> SWIPE_ROUTES = List.of(
            new NotificationSwipeRouteView("/reinvest", "commission", "奖励到账 -> 锁定计划"),
            new NotificationSwipeRouteView("/me/bills", "refund", "退款到账 -> 账单"),
            new NotificationSwipeRouteView("-", "system", "维护公告 / 监管通告 / 运营公告 - system kind 无转化跳转"));
    private static final DateTimeFormatter CAMPAIGN_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter SCHEDULE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[A-Za-z][A-Za-z0-9_]*}");

    private final NotificationCampaignRepository campaignRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AuditObjectLockMapper lockMapper;
    private final PlatformConfigFacade configFacade;
    private final NotificationCampaignDispatchExecutor dispatchExecutor;
    private final AdminIdempotencyService idempotencyService;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ApiResult<NotificationCampaignOverview> overview() {
        var instant = clock.instant();
        var calendar = BusinessCalendar.legacy();
        LocalDateTime asOf = LocalDateTime.ofInstant(instant, BusinessCalendar.STORAGE_ZONE);
        var date = calendar.dateAt(instant);
        LocalDateTime monthFrom = calendar.storageStart(date.withDayOfMonth(1));
        LocalDateTime weekFrom = calendar.storageStart(date.minusDays(date.getDayOfWeek().getValue() - 1L));
        Map<String, CampaignDeliveryFacts> facts = new LinkedHashMap<>();
        campaignRepository.campaignDeliveryFacts(asOf, weekFrom).forEach(row -> facts.put(row.campaignNo(), row));
        List<NotificationCampaignRow> campaigns = campaignRepository.listCampaigns().stream().map(row -> {
            CampaignDeliveryFacts fact = facts.get(row.id());
            boolean complete = fact != null && fact.sentCount() > 0
                    && fact.linkedNotifications() == fact.sentCount()
                    && Long.toString(fact.sentCount()).equals(row.sent());
            Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("source", "NOTIFICATION_DELIVERED_OUTBOX_TO_NOTIFICATION_ID");
            projection.put("timeZone", BusinessCalendar.STORAGE_ZONE.getId());
            projection.put("asOf", instant.toString());
            projection.put("complete", complete);
            projection.put("reason", complete ? null : "DELIVERY_FACT_COVERAGE_UNCONFIRMED");
            projection.put("sentCount", fact == null || fact.sentCount() == 0 ? null : fact.sentCount());
            projection.put("readCount", fact == null || fact.sentCount() == 0 ? null : fact.readCount());
            projection.put("firstSentAt", fact == null || fact.firstSentAt() == null ? null
                    : fact.firstSentAt().atZone(BusinessCalendar.STORAGE_ZONE).toOffsetDateTime().toString());
            projection.put("lastSentAt", fact == null || fact.lastSentAt() == null ? null
                    : fact.lastSentAt().atZone(BusinessCalendar.STORAGE_ZONE).toOffsetDateTime().toString());
            projection.put("timeBasis", "RETAINED_LINKED_NOTIFICATION_PUSHED_AT_WINDOW");
            return new NotificationCampaignRow(row.id(), row.name(), row.kind(), row.tier(), row.audience(),
                    row.reach(), row.status(), row.schedule(), complete ? Long.toString(fact.sentCount()) : row.sent(),
                    complete ? Long.toString(fact.readCount()) : null, row.bodyEn(), row.bodyZh(), row.bodyVi(),
                    row.swipeTo(), row.budget(), row.audienceTarget(), row.ctaLabel(), row.ctaHref(),
                    row.revision(), row.translations(), projection);
        }).toList();
        return ApiResult.ok(new NotificationCampaignOverview(
                stats(campaigns, facts, monthFrom, weekFrom, asOf, calendar.zoneAt(instant).getId()),
                campaigns,
                campaignRepository.listCapRules(),
                List.copyOf(TIERS),
                campaigns.stream().map(NotificationCampaignRow::audience).distinct().toList(),
                List.copyOf(ACTIVE_STATUSES),
                SWIPE_ROUTES,
                audienceCatalog(),
                deliveryCatalog(),
                List.of("nx_notification_campaign", "nx_notification_cap_rule", "nx_notification")));
    }

    public ApiResult<NotificationCampaignRow> createCampaign(String idempotencyKey, NotificationCampaignCreateRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeIdempotent("I3_NOTIFICATION_CAMPAIGN_CREATE", idempotencyKey,
                requestHash("create", operator(request.operator()), request), () -> {
                    ApiResult<NotificationCampaignRow> budgetGuard = requireBudgetPrecision(request.budget());
                    if (budgetGuard != null) return budgetGuard;
                    NotificationCampaignCreateRequest normalized = normalizeCreate(request);
                    if (dispatchTextTooLong(normalized)) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_TEXT_TOO_LONG");
                    }
                    long estimatedAudience = campaignRepository.estimateAudience(normalized.audienceTarget(), currentPhase(), now());
                    String campaignNo = nextCampaignNo(normalized.name());
                    NotificationCampaignRow created = campaignRepository.createCampaign(campaignNo, normalized, estimatedAudience, now());
                    audit("I3_NOTIFICATION_CAMPAIGN_CREATED", created.id(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                            "tier", created.tier(),
                            "audience", created.audience(),
                            "status", created.status()));
                    return ApiResult.ok(created);
                });
    }

    public ApiResult<NotificationCampaignRow> updateDraft(String campaignNo, String idempotencyKey, NotificationCampaignDraftRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireDraft(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeIdempotent("I3_NOTIFICATION_CAMPAIGN_DRAFT_UPDATE", idempotencyKey,
                requestHash("draft", campaignNo, operator(request.operator()), request), () -> {
                    NotificationCampaignRow current = findCampaign(campaignNo);
                    if (current == null) {
                        return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
                    }
                    if (!"draft".equals(current.status())) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    long expectedRevision = request.expectedRevision();
                    if (expectedRevision != current.revision()) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    ApiResult<NotificationCampaignRow> budgetGuard = requireBudgetPrecision(request.budget());
                    if (budgetGuard != null) return budgetGuard;
                    NotificationCampaignDraftRequest normalized = normalizeDraft(request);
                    if (dispatchTextTooLong(normalized)) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_TEXT_TOO_LONG");
                    }
                    long estimatedAudience = campaignRepository.estimateAudience(normalized.audienceTarget(), currentPhase(), now());
                    if (!campaignRepository.updateDraft(current.id(), normalized, estimatedAudience, expectedRevision, now())) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    NotificationCampaignRow updated = findCampaign(current.id());
                    audit("I3_NOTIFICATION_CAMPAIGN_DRAFT_SAVED", current.id(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                            "fromStatus", current.status(),
                            "toStatus", updated.status(),
                            "tier", updated.tier(),
                            "fromRevision", current.revision(),
                            "toRevision", updated.revision()));
                    return ApiResult.ok(updated);
                });
    }

    public ApiResult<NotificationCampaignRow> scheduleCampaign(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeIdempotent("I3_NOTIFICATION_CAMPAIGN_SCHEDULE", idempotencyKey,
                requestHash("schedule", campaignNo, operator(request.operator()), request), () -> {
                    NotificationCampaignRow current = findCampaign(campaignNo);
                    if (current == null) {
                        return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
                    }
                    if (!"draft".equals(current.status())) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    long expectedRevision = request.expectedRevision();
                    if (expectedRevision != current.revision()) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    if (dispatchTextTooLong(current)) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_TEXT_TOO_LONG");
                    }
                    ApiResult<NotificationCampaignRow> criticalGuard = requireCriticalExecutionAuthority(current);
                    if (criticalGuard != null) {
                        return criticalGuard;
                    }
                    String phase = currentPhase();
                    if (!PHASES.contains(phase)) {
                        return ApiResult.fail(503, "NOTIFICATION_CURRENT_PHASE_UNAVAILABLE");
                    }
                    if (campaignRepository.estimateAudience(current.audienceTarget(), phase, now()) <= 0) {
                        return ApiResult.fail(422, "AUDIENCE_EMPTY");
                    }
                    if (!StringUtils.hasText(request.schedule())) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_SCHEDULE_REQUIRED");
                    }
                    LocalDateTime scheduledAt;
                    try {
                        scheduledAt = LocalDateTime.parse(request.schedule().trim());
                    } catch (DateTimeParseException ex) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_SCHEDULE_INVALID");
                    }
                    if (!scheduledAt.isAfter(now())) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_SCHEDULE_MUST_BE_FUTURE");
                    }
                    String schedule = scheduledAt.format(SCHEDULE_TIME);
                    String authenticatedOperator = operator(request.operator());
                    if (!campaignRepository.scheduleDraft(current.id(), schedule, authenticatedOperator, expectedRevision, now())) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    NotificationCampaignRow updated = findCampaign(current.id());
                    audit("I3_NOTIFICATION_CAMPAIGN_SCHEDULED", current.id(), authenticatedOperator, idempotencyKey, request.reason(), Map.of(
                            "schedule", updated.schedule(),
                            "fromRevision", current.revision(),
                            "toRevision", updated.revision()));
                    return ApiResult.ok(updated);
                });
    }

    public ApiResult<NotificationCampaignRow> sendNow(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeIdempotent("I3_NOTIFICATION_CAMPAIGN_SEND_NOW", idempotencyKey,
                requestHash("send-now", campaignNo, operator(request.operator()), request), () -> {
                    NotificationCampaignRow current = findCampaign(campaignNo);
                    if (current == null) {
                        return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
                    }
                    if (!Set.of("draft", "scheduled").contains(current.status())) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    long expectedRevision = request.expectedRevision();
                    if (expectedRevision != current.revision()) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    if (dispatchTextTooLong(current)) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_TEXT_TOO_LONG");
                    }
                    ApiResult<NotificationCampaignRow> criticalGuard = requireCriticalExecutionAuthority(current);
                    if (criticalGuard != null) {
                        return criticalGuard;
                    }
                    String phase = currentPhase();
                    if (!PHASES.contains(phase)) {
                        return ApiResult.fail(503, "NOTIFICATION_CURRENT_PHASE_UNAVAILABLE");
                    }
                    // Reject a deterministically empty audience before the REQUIRED dispatch
                    // transaction performs its CAS claim. If AudienceEmptyException is caught
                    // after that claim, Spring has already marked the shared idempotency
                    // transaction rollback-only and the intended 422 is replaced by a 500 at
                    // commit time. The executor keeps its own check as the race-condition guard.
                    if (campaignRepository.estimateAudience(current.audienceTarget(), phase, now()) <= 0) {
                        return ApiResult.fail(422, "AUDIENCE_EMPTY");
                    }
                    LocalDateTime dispatchTime = now();
                    try {
                        dispatchExecutor.dispatchImmediate(
                                current.id(),
                                "i3:send:" + idempotencyKey.trim(),
                                phase,
                                operator(request.operator()),
                                idempotencyKey.trim(),
                                request.reason().trim(),
                                expectedRevision,
                                dispatchTime);
                    } catch (NotificationCampaignDispatchExecutor.AudienceEmptyException ex) {
                        return ApiResult.fail(422, "AUDIENCE_EMPTY");
                    } catch (NotificationCampaignDispatchExecutor.ConcurrentDispatchException ex) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    } catch (RuntimeException ex) {
                        return ApiResult.fail(500, "NOTIFICATION_CAMPAIGN_DISPATCH_FAILED");
                    }
                    return ApiResult.ok(findCampaign(current.id()));
                });
    }

    public ApiResult<NotificationCampaignRow> cancelScheduled(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeIdempotent("I3_NOTIFICATION_CAMPAIGN_CANCEL", idempotencyKey,
                requestHash("cancel", campaignNo, operator(request.operator()), request), () -> {
                    NotificationCampaignRow current = findCampaign(campaignNo);
                    if (current == null) {
                        return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
                    }
                    if (!"scheduled".equals(current.status())) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    long expectedRevision = request.expectedRevision();
                    if (expectedRevision != current.revision()) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    ApiResult<NotificationCampaignRow> criticalGuard = requireCriticalExecutionAuthority(current);
                    if (criticalGuard != null) {
                        return criticalGuard;
                    }
                    String authenticatedOperator = operator(request.operator());
                    if (!campaignRepository.cancelScheduled(current.id(), authenticatedOperator, expectedRevision, now())) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    NotificationCampaignRow updated = findCampaign(current.id());
                    audit("I3_NOTIFICATION_CAMPAIGN_CANCELLED", current.id(), authenticatedOperator, idempotencyKey, request.reason(), Map.of(
                            "fromStatus", current.status(),
                            "fromRevision", current.revision(),
                            "toRevision", updated.revision()));
                    return ApiResult.ok(updated);
                });
    }

    public ApiResult<Void> deleteDraft(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        return executeIdempotent("I3_NOTIFICATION_CAMPAIGN_DELETE", idempotencyKey,
                requestHash("delete", campaignNo, operator(request.operator()), request), () -> {
                    NotificationCampaignRow current = findCampaign(campaignNo);
                    if (current == null) {
                        return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
                    }
                    if (!Set.of("draft", "cancelled").contains(current.status())) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    long expectedRevision = request.expectedRevision();
                    if (expectedRevision != current.revision()) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    if (!campaignRepository.deleteDraft(current.id(), expectedRevision, now())) {
                        return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_STALE_VERSION");
                    }
                    String authenticatedOperator = operator(request.operator());
                    audit("I3_NOTIFICATION_CAMPAIGN_DELETED", current.id(), authenticatedOperator, idempotencyKey, request.reason(), Map.of(
                            "fromStatus", current.status(),
                            "fromRevision", current.revision()));
                    return ApiResult.ok(null);
                });
    }

    public ApiResult<NotificationAudienceEstimateView> estimateAudience(NotificationAudienceEstimateRequest request) {
        NotificationAudienceTarget target = request == null ? null : normalizeAudience(request.target());
        String error = audienceValidationError(target);
        if (error != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), error);
        }
        return ApiResult.ok(new NotificationAudienceEstimateView(
                target,
                campaignRepository.estimateAudience(target, currentPhase(), now())));
    }

    public int dispatchDueScheduledCampaigns() {
        LocalDateTime dispatchTime = now();
        String phase = currentPhase();
        campaignRepository.recoverStaleSending(dispatchTime.minusMinutes(5), dispatchTime);
        int completed = 0;
        for (String campaignNo : campaignRepository.listDueScheduledCampaignNos(dispatchTime, 100)) {
            if (!PHASES.contains(phase)) {
                campaignRepository.completeDispatch(campaignNo, "FAILED", 0, "当前平台阶段不可用", "system", dispatchTime);
                continue;
            }
            try {
                if (dispatchExecutor.dispatchScheduled(campaignNo, phase, dispatchTime) > 0) {
                    completed++;
                }
            } catch (RuntimeException ignored) {
                // The independent transaction rolls claim and inserts back; the next scheduler tick retries safely.
            }
        }
        return completed;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<NotificationCapRuleView> updateCapRule(String tier, String idempotencyKey, NotificationCapUpdateRequest request) {
        return updateCapRuleInternal(tier, idempotencyKey, request, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<NotificationCapRuleView> updateApprovedCapRule(String tier, String idempotencyKey, NotificationCapUpdateRequest request) {
        if (A2ReplayContext.operationId() == null) {
            return ApiResult.fail(403, "NOTIFICATION_CAP_APPROVAL_REQUIRED");
        }
        return updateCapRuleInternal(tier, idempotencyKey, request, true);
    }

    private ApiResult<NotificationCapRuleView> updateCapRuleInternal(String tier, String idempotencyKey,
            NotificationCapUpdateRequest request, boolean approved) {
        String normalizedTier = normalizeTier(tier);
        if (!TIERS.contains(normalizedTier)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_TIER_UNSUPPORTED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        String normalizedCap = request == null ? null : normalizeCap(request.cap());
        if (normalizedCap == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAP_INVALID");
        }
        String expectedCap = request == null ? "" : trimToEmpty(request.expectedCap());
        if (!StringUtils.hasText(expectedCap) || expectedCap.length() > 160) {
            return ApiResult.fail(409, "NOTIFICATION_CAP_EXPECTED_REQUIRED");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return executeIdempotent("I3_NOTIFICATION_CAP_UPDATE", idempotencyKey,
                requestHash("cap", normalizedTier, operator(request.operator()), request), () -> {
                    if (!approved) {
                        // Direct PATCH has no approved A2 command/review context; reject before
                        // reading the cap row, applying retention, or writing success audit.
                        return ApiResult.fail(403, "NOTIFICATION_CAP_APPROVAL_REQUIRED");
                    }
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("I", "notification_cap", normalizedTier) > 0) {
                        return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
                    }
                    NotificationCapRuleView current = campaignRepository.findCapRule(normalizedTier).orElse(null);
                    if (current == null) {
                        return ApiResult.fail(404, "NOTIFICATION_CAP_NOT_FOUND");
                    }
                    if (current.locked()) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOTIFICATION_CAP_LOCKED");
                    }
                    if (!expectedCap.equals(current.cap())) {
                        return ApiResult.fail(409, "NOTIFICATION_CAP_STALE_VERSION");
                    }
                    if (normalizedCap.equals(normalizeCap(current.cap()))) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    String authenticatedOperator = operator(request.operator());
                    LocalDateTime mutationTime = now();
                    if (!campaignRepository.updateCapRuleIfCurrent(
                            normalizedTier,
                            normalizedCap,
                            current.cap(),
                            authenticatedOperator,
                            mutationTime)) {
                        return ApiResult.fail(409, "NOTIFICATION_CAP_STALE_VERSION");
                    }
                    campaignRepository.applyRetention(mutationTime);
                    NotificationCapRuleView updated = campaignRepository.findCapRule(normalizedTier).orElseThrow();
                    auditRequired("I3_NOTIFICATION_CAP_CHANGED", normalizedTier, authenticatedOperator, idempotencyKey, request.reason(), Map.of(
                            "from", current.cap(),
                            "to", updated.cap()));
                    return ApiResult.ok(updated);
                });
    }

    /** Delivered notification columns are 128/512 characters; validate the actual (title, body) pair. */
    private boolean dispatchTextTooLong(NotificationCampaignCreateRequest request) {
        if (request == null) return false;
        if (deliveredTooLong(request.titleZh(), request.bodyZh(), request.name())
                || deliveredTooLong(request.titleVi(), request.bodyVi(), request.name())
                || deliveredTooLong(request.titleEn(), request.bodyEn(), "UVEL notification")) return true;
        return translationsTooLong(request.translations());
    }

    private boolean dispatchTextTooLong(NotificationCampaignDraftRequest request) {
        if (request == null) return false;
        if (deliveredTooLong(request.titleZh(), request.bodyZh(), request.name())
                || deliveredTooLong(request.titleVi(), request.bodyVi(), request.name())
                || deliveredTooLong(request.titleEn(), request.bodyEn(), "UVEL notification")) return true;
        return translationsTooLong(request.translations());
    }

    private boolean dispatchTextTooLong(NotificationCampaignRow row) {
        if (row == null) return false;
        if (combinedTooLongFromBody(row.bodyZh(), row.name())
                || combinedTooLongFromBody(row.bodyVi(), row.name())
                || combinedTooLongFromBody(row.bodyEn(), "UVEL notification")) return true;
        return translationsTooLong(row.translations());
    }

    private boolean deliveredTooLong(String title, String body, String fallbackTitle) {
        return combinedTooLongFromBody(text(title) + "\n" + text(body), fallbackTitle);
    }

    /** Mirrors Repository.localizedTitle/localizedContent: split raw before trim, then trim each part. */
    private boolean combinedTooLongFromBody(String combined, String fallbackTitle) {
        boolean hasContent = combined != null && !combined.trim().isEmpty();
        if (!hasContent) return codePointLength(fallbackTitle) > 128;
        int newline = combined.indexOf('\n');
        String rawTitle = newline < 0 ? combined : combined.substring(0, newline);
        String title = rawTitle.trim();
        if (title.isEmpty()) title = fallbackTitle;
        String body = newline < 0 ? combined.trim() : combined.substring(newline + 1).trim();
        return codePointLength(title) > 128 || codePointLength(body) > 512;
    }

    private boolean translationsTooLong(Map<String, NotificationTranslation> translations) {
        if (translations == null) return false;
        for (NotificationTranslation translation : translations.values()) {
            if (translation != null && (codePointLength(translation.title()) > 128
                    || codePointLength(translation.body()) > 512)) return true;
        }
        return false;
    }

    private int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Applies only to a new write intent after idempotency replay. DECIMAL(18,2)
     * stores two fractional digits; trailing zeros are not a value change, but a
     * non-zero fraction beyond two digits would be silently rounded by MySQL.
     */
    private ApiResult<NotificationCampaignRow> requireBudgetPrecision(BigDecimal budget) {
        if (budget != null && budget.stripTrailingZeros().scale() > 2) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_BUDGET_INVALID");
        }
        return null;
    }

    private ApiResult<NotificationCampaignRow> requireCreate(String idempotencyKey, NotificationCampaignCreateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.name()) || !StringUtils.hasText(request.titleZh())
                || !StringUtils.hasText(request.titleVi()) || !StringUtils.hasText(request.bodyZh()) || !StringUtils.hasText(request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_FIELDS_REQUIRED");
        }
        if (request.name().trim().length() > 160 || request.titleZh().trim().length() > 160 || request.titleVi().trim().length() > 160
                || trimToEmpty(request.titleEn()).length() > 160 || request.bodyZh().trim().length() > 4000
                || request.bodyVi().trim().length() > 4000 || trimToEmpty(request.bodyEn()).length() > 4000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_TEXT_TOO_LONG");
        }
        if (JSON_LIKE_PATTERN.matcher(request.bodyZh()).find() || JSON_LIKE_PATTERN.matcher(request.bodyVi()).find()
                || JSON_LIKE_PATTERN.matcher(trimToEmpty(request.bodyEn())).find()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_JSON_NOT_ALLOWED");
        }
        if (!placeholders(request.bodyZh()).equals(placeholders(request.bodyVi()))
                || (StringUtils.hasText(request.bodyEn()) && !placeholders(request.bodyZh()).equals(placeholders(request.bodyEn())))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_PLACEHOLDERS_MISMATCH");
        }
        if (!validTranslations(request.translations(), request.titleZh(), request.bodyZh())) {
            return ApiResult.fail(422, "NOTIFICATION_TRANSLATIONS_INVALID");
        }
        if (!TIERS.contains(normalizeTier(request.tier()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_TIER_UNSUPPORTED");
        }
        String audienceError = audienceValidationError(normalizeAudience(request.audienceTarget()));
        if (audienceError != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), audienceError);
        }
        if (request.budget() != null && request.budget().compareTo(BigDecimal.ZERO) < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_BUDGET_INVALID");
        }
        if (!validDelivery(request.kind(), request.ctaLabel(), request.ctaHref())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_DELIVERY_CONFIG_INVALID");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<NotificationCampaignRow> requireDraft(String idempotencyKey, NotificationCampaignDraftRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.name()) || !StringUtils.hasText(request.titleZh())
                || !StringUtils.hasText(request.titleVi()) || !StringUtils.hasText(request.bodyZh()) || !StringUtils.hasText(request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_DRAFT_REQUIRED");
        }
        if (request.expectedRevision() == null) {
            return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_REVISION_REQUIRED");
        }
        if (request.name().trim().length() > 160 || request.titleZh().trim().length() > 160
                || request.titleVi().trim().length() > 160 || trimToEmpty(request.titleEn()).length() > 160
                || request.bodyZh().trim().length() > 4000 || request.bodyVi().trim().length() > 4000
                || trimToEmpty(request.bodyEn()).length() > 4000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_TEXT_TOO_LONG");
        }
        if (!validTranslations(request.translations(), request.titleZh(), request.bodyZh())) {
            return ApiResult.fail(422, "NOTIFICATION_TRANSLATIONS_INVALID");
        }
        if (!TIERS.contains(normalizeTier(request.tier()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_TIER_UNSUPPORTED");
        }
        String audienceError = audienceValidationError(normalizeAudience(request.audienceTarget()));
        if (audienceError != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), audienceError);
        }
        if (request.budget() != null && request.budget().compareTo(BigDecimal.ZERO) < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_BUDGET_INVALID");
        }
        if (JSON_LIKE_PATTERN.matcher(request.bodyZh()).find() || JSON_LIKE_PATTERN.matcher(request.bodyVi()).find()
                || JSON_LIKE_PATTERN.matcher(trimToEmpty(request.bodyEn())).find()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_JSON_NOT_ALLOWED");
        }
        if (!placeholders(request.bodyZh()).equals(placeholders(request.bodyVi()))
                || (StringUtils.hasText(request.bodyEn()) && !placeholders(request.bodyZh()).equals(placeholders(request.bodyEn())))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_PLACEHOLDERS_MISMATCH");
        }
        if (!validDelivery(request.kind(), request.ctaLabel(), request.ctaHref())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_DELIVERY_CONFIG_INVALID");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<NotificationCampaignRow> requireAction(String idempotencyKey, NotificationCampaignActionRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (request.expectedRevision() == null) {
            return ApiResult.fail(409, "NOTIFICATION_CAMPAIGN_REVISION_REQUIRED");
        }
        return null;
    }

    private boolean validTranslations(Map<String, NotificationTranslation> translations, String title, String body) {
        if (translations == null) return true;
        return translations.entrySet().stream().allMatch(entry -> {
            NotificationTranslation value = entry.getValue();
            if ("en".equals(entry.getKey())) return value != null && value.title().isEmpty()
                    && value.body().isEmpty() && value.ctaLabel().length() <= 80;
            return InterfaceLanguages.isAdditional(entry.getKey()) && value != null
                    && value.title().length() <= 160 && value.body().length() <= 4000 && value.ctaLabel().length() <= 80
                    && !JSON_LIKE_PATTERN.matcher(value.body()).find()
                    && (value.title().isEmpty() || placeholders(title).equals(placeholders(value.title())))
                    && (value.body().isEmpty() || placeholders(body).equals(placeholders(value.body())));
        });
    }

    private NotificationCampaignCreateRequest normalizeCreate(NotificationCampaignCreateRequest request) {
        return new NotificationCampaignCreateRequest(
                request.name().trim(),
                request.titleZh().trim(),
                request.titleVi().trim(),
                trimToEmpty(request.titleEn()),
                request.bodyZh().trim(),
                request.bodyVi().trim(),
                trimToEmpty(request.bodyEn()),
                normalizeTier(request.tier()),
                normalizeAudience(request.audienceTarget()),
                request.budget(),
                operator(request.operator()),
                request.reason().trim(),
                normalizeKind(request.kind()),
                trimToEmpty(request.ctaLabel()),
                trimToEmpty(request.ctaHref()), request.translations());
    }

    private NotificationCampaignDraftRequest normalizeDraft(NotificationCampaignDraftRequest request) {
        return new NotificationCampaignDraftRequest(
                request.name().trim(),
                request.titleZh().trim(),
                request.titleVi().trim(),
                trimToEmpty(request.titleEn()),
                request.bodyZh().trim(),
                request.bodyVi().trim(),
                trimToEmpty(request.bodyEn()),
                normalizeTier(request.tier()),
                normalizeAudience(request.audienceTarget()),
                request.budget(),
                operator(request.operator()),
                request.reason().trim(),
                normalizeKind(request.kind()),
                trimToEmpty(request.ctaLabel()),
                trimToEmpty(request.ctaHref()), request.expectedRevision(), request.translations());
    }

    private NotificationCampaignRow findCampaign(String campaignNo) {
        return StringUtils.hasText(campaignNo) ? campaignRepository.findCampaign(campaignNo.trim()).orElse(null) : null;
    }

    private String currentPhase() {
        return configFacade.activeValue(CURRENT_PHASE_KEY)
                .map(this::normalizePhase)
                .filter(PHASES::contains)
                .orElse("");
    }

    private ApiResult<NotificationCampaignRow> requireCriticalExecutionAuthority(NotificationCampaignRow campaign) {
        if (!"critical".equalsIgnoreCase(campaign.tier())) {
            return null;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authorized = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority ->
                "content_i3_critical_send".equals(authority.getAuthority()));
        return authorized ? null : ApiResult.fail(403, "NOTIFICATION_CRITICAL_EXECUTION_FORBIDDEN");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private Set<String> placeholders(String value) {
        var values = new java.util.LinkedHashSet<String>();
        var matcher = PLACEHOLDER_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private NotificationCampaignStats stats(List<NotificationCampaignRow> campaigns,
            Map<String, CampaignDeliveryFacts> facts, LocalDateTime monthFrom, LocalDateTime weekFrom,
            LocalDateTime asOf, String timeZone) {
        List<NotificationCampaignRow> monthly = campaigns.stream().filter(row -> {
            CampaignDeliveryFacts fact = facts.get(row.id());
            return fact != null && fact.campaignCreatedAt() != null
                    && !fact.campaignCreatedAt().isBefore(monthFrom) && fact.campaignCreatedAt().isBefore(asOf);
        }).toList();
        int sent = (int) monthly.stream().filter(row -> "sent".equals(row.status())).count();
        int scheduled = (int) monthly.stream().filter(row -> "scheduled".equals(row.status()) || "sending".equals(row.status())).count();
        int draft = (int) monthly.stream().filter(row -> "draft".equals(row.status())).count();
        int criticalInflight = (int) campaigns.stream()
                .filter(row -> "critical".equals(row.tier()))
                .filter(row -> "scheduled".equals(row.status()) || "sending".equals(row.status()))
                .count();
        List<CampaignDeliveryFacts> sentFacts = campaigns.stream().filter(row -> "sent".equals(row.status()))
                .map(row -> facts.get(row.id())).filter(java.util.Objects::nonNull).toList();
        long delivered = sentFacts.stream().mapToLong(CampaignDeliveryFacts::sentCount).sum();
        long read = sentFacts.stream().mapToLong(CampaignDeliveryFacts::readCount).sum();
        boolean complete = campaigns.stream().filter(row -> "sent".equals(row.status()))
                .allMatch(row -> Boolean.TRUE.equals(row.deliveryFacts().get("complete")));
        long weekRead = sentFacts.stream().mapToLong(CampaignDeliveryFacts::weeklyReadCount).sum();
        long undatedRead = sentFacts.stream().mapToLong(CampaignDeliveryFacts::undatedReadCount).sum();
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("asOf", asOf.toString());
        basis.put("timeZone", timeZone);
        basis.put("storageTimeZone", BusinessCalendar.STORAGE_ZONE.getId());
        basis.put("monthFrom", monthFrom.toString());
        basis.put("weekFrom", weekFrom.toString());
        basis.put("rangeEndExclusive", asOf.toString());
        basis.put("monthBasis", "CAMPAIGN_CREATED_AT_COHORT_WITH_CURRENT_STATUS");
        basis.put("notificationBasis", "RETAINED_LINKED_NOTIFICATIONS_OF_CURRENT_SENT_CAMPAIGNS");
        basis.put("deliveryFactsComplete", complete);
        basis.put("knownSentCount", delivered);
        basis.put("knownReadCount", read);
        basis.put("weeklyReadCount", complete && undatedRead == 0 ? weekRead : null);
        basis.put("weeklyReadKnownCount", weekRead);
        basis.put("undatedReadCount", undatedRead);
        basis.put("weeklyReadAvailable", complete && undatedRead == 0);
        basis.put("weeklyReadBasis", "NOTIFICATION_READ_AT_WITH_READ_FLAG");
        basis.put("weeklySwipeAvailable", false);
        basis.put("weeklySwipeReason", "LEGACY_ESTIMATED_CONVERSION_REMOVED");
        return new NotificationCampaignStats(
                monthly.size(),
                sent,
                scheduled,
                draft,
                criticalInflight,
                complete && delivered > 0 ? String.format(Locale.ROOT, "%.1f%%", read * 100.0 / delivered) : null,
                null, basis);
    }

    private NotificationAudienceCatalog audienceCatalog() {
        return new NotificationAudienceCatalog(
                PHASES.stream().map(value -> new NotificationAudienceOption(value, value)).toList(),
                List.of(
                        new NotificationAudienceOption("all", "全部语言"),
                        new NotificationAudienceOption("zh", "中文"),
                        new NotificationAudienceOption("vi", "越南语"),
                        new NotificationAudienceOption("en", "英文"),
                        new NotificationAudienceOption("id", "印尼语"),
                        new NotificationAudienceOption("ms", "马来语"),
                        new NotificationAudienceOption("fil", "菲律宾语"),
                        new NotificationAudienceOption("ru", "俄语"),
                        new NotificationAudienceOption("kk", "哈萨克语"),
                        new NotificationAudienceOption("uz", "乌兹别克语")),
                "AND");
    }

    private NotificationDeliveryCatalog deliveryCatalog() {
        return new NotificationDeliveryCatalog(
                List.of(
                        new NotificationAudienceOption("system", "系统通知"),
                        new NotificationAudienceOption("commission", "奖励通知"),
                        new NotificationAudienceOption("team", "团队通知"),
                        new NotificationAudienceOption("staking", "质押通知"),
                        new NotificationAudienceOption("market", "市场通知"),
                        new NotificationAudienceOption("genesis", "Genesis 通知")),
                CTA_ROUTES);
    }

    private boolean validDelivery(String kind, String ctaLabel, String ctaHref) {
        String normalizedKind = normalizeKind(kind);
        String normalizedHref = trimToEmpty(ctaHref);
        if (!KINDS.contains(normalizedKind) || trimToEmpty(ctaLabel).length() > 64) return false;
        if (normalizedHref.length() > 255 || CTA_ROUTES.stream().noneMatch(route -> route.value().equals(normalizedHref))) {
            return false;
        }
        return normalizedHref.isEmpty() || StringUtils.hasText(ctaLabel);
    }

    private boolean validReason(String reason) {
        int length = StringUtils.hasText(reason) ? reason.trim().length() : 0;
        return length >= 8 && length <= 200;
    }

    private String normalizeKind(String kind) {
        return StringUtils.hasText(kind) ? kind.trim().toLowerCase(Locale.ROOT) : "system";
    }

    private NotificationAudienceTarget normalizeAudience(NotificationAudienceTarget target) {
        if (target == null) {
            return null;
        }
        return new NotificationAudienceTarget(
                normalizePhase(target.phaseMin()),
                normalizePhase(target.phaseMax()),
                StringUtils.hasText(target.language()) ? target.language().trim().toLowerCase(Locale.ROOT) : "",
                target.registrationDaysMin());
    }

    private String audienceValidationError(NotificationAudienceTarget target) {
        if (target == null || !PHASES.contains(target.phaseMin()) || !PHASES.contains(target.phaseMax())) {
            return "NOTIFICATION_AUDIENCE_PHASE_REQUIRED";
        }
        if (PHASES.indexOf(target.phaseMin()) > PHASES.indexOf(target.phaseMax())) {
            return "NOTIFICATION_AUDIENCE_PHASE_RANGE_INVALID";
        }
        if (!"all".equals(target.language()) && !InterfaceLanguages.CODES.contains(target.language())) {
            return "NOTIFICATION_AUDIENCE_LANGUAGE_UNSUPPORTED";
        }
        if (target.registrationDaysMin() == null || target.registrationDaysMin() < 0 || target.registrationDaysMin() > 36500) {
            return "NOTIFICATION_AUDIENCE_REGISTRATION_DAYS_INVALID";
        }
        return null;
    }

    private String normalizePhase(String phase) {
        return StringUtils.hasText(phase) ? phase.trim().toUpperCase(Locale.ROOT) : "";
    }

    private double quantity(String value) {
        if (!StringUtils.hasText(value) || "-".equals(value.trim())) {
            return 0;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        double multiplier = normalized.endsWith("K") ? 1000 : 1;
        String number = normalized.replaceAll("[^0-9.]", "");
        if (!StringUtils.hasText(number)) {
            return 0;
        }
        try {
            return Double.parseDouble(number) * multiplier;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String compactNumber(double value) {
        if (value >= 1000) {
            return String.format(Locale.ROOT, "%.1fK", value / 1000.0);
        }
        return String.valueOf(Math.round(value));
    }

    private String nextCampaignNo(String name) {
        return "CMP-N-" + slug(name) + "-" + LocalDateTime.now(clock).format(CAMPAIGN_TIME);
    }

    private String slug(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        if (!StringUtils.hasText(normalized)) {
            return "campaign";
        }
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40).replaceAll("-+$", "");
    }

    private String normalizeTier(String tier) {
        return StringUtils.hasText(tier) ? tier.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeCap(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        if (!trimmed.matches("^[0-9]+(?:\\s*条)?$")) return null;
        String digits = trimmed.replaceAll("\\D", "");
        try {
            int count = Integer.parseInt(digits);
            return count >= 1 && count <= 10000 ? count + " 条" : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> executeIdempotent(
            String scope,
            String idempotencyKey,
            String requestHash,
            Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                requestHash,
                ApiResult.class,
                (Supplier) action);
    }

    private String requestHash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String operator(String claimedOperator) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            if (authentication.getDetails() instanceof Map<?, ?> details
                    && "ADMIN".equalsIgnoreCase(String.valueOf(details.get("subjectType")))) {
                Object username = details.get("username");
                if (username != null && StringUtils.hasText(String.valueOf(username))) {
                    return String.valueOf(username).trim();
                }
            }
            if (authentication.getPrincipal() != null
                    && StringUtils.hasText(authentication.getName())
                    && !"anonymousUser".equals(authentication.getName())) {
                return authentication.getName().trim();
            }
        }
        return StringUtils.hasText(claimedOperator) ? claimedOperator.trim() : "system";
    }

    private void audit(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        auditLogService.recordRequired(auditRequest(action, resourceId, operator, idempotencyKey, reason, extra));
    }

    private void auditRequired(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        auditLogService.recordRequired(auditRequest(action, resourceId, operator, idempotencyKey, reason, extra));
    }

    private AuditLogWriteRequest auditRequest(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        return AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(action.contains("CAP") ? "NOTIFICATION_CAP" : "NOTIFICATION_CAMPAIGN")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build();
    }
}
