package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NotificationCampaignOverview;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationCampaignStats;
import ffdd.opsconsole.content.domain.NotificationAudienceCatalog;
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
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
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
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.security.core.context.SecurityContextHolder;

@ApplicationService
@RequiredArgsConstructor
public class OpsNotificationCampaignService {
    private static final Set<String> TIERS = Set.of("critical", "high", "normal", "low");
    private static final Set<String> ACTIVE_STATUSES = Set.of("draft", "scheduled", "sending", "sent", "failed", "cancelled");
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final List<String> PHASES = List.of("P1", "P2", "P3", "P4", "P5", "P6");
    private static final Set<String> LANGUAGES = Set.of("all", "zh", "vi", "en");
    private static final Set<String> KINDS = Set.of("system", "commission", "team", "staking", "market", "genesis");
    private static final List<NotificationAudienceOption> CTA_ROUTES = List.of(
            new NotificationAudienceOption("", "无跳转"),
            new NotificationAudienceOption("/pages/me/wallet-repurchase", "我的 · 钱包复投"),
            new NotificationAudienceOption("/pages/team/team", "赚取 · 团队"),
            new NotificationAudienceOption("/pages/staking/staking", "赚取 · 质押"),
            new NotificationAudienceOption("/pages/market/market", "商城 · 市场"),
            new NotificationAudienceOption("/pages/genesis/marketplace", "商城 · Genesis"),
            new NotificationAudienceOption("/pages/me/kyc", "我的 · 身份认证"));
    private static final List<NotificationSwipeRouteView> SWIPE_ROUTES = List.of(
            new NotificationSwipeRouteView("/reinvest", "commission", "佣金到账 -> 复投"),
            new NotificationSwipeRouteView("/me/bills", "refund", "退款到账 -> 账单"),
            new NotificationSwipeRouteView("-", "system", "维护公告 / KYC 提醒 / 监管通告 / 运营公告 - system kind 无转化跳转"));
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

    public ApiResult<NotificationCampaignOverview> overview() {
        List<NotificationCampaignRow> campaigns = campaignRepository.listCampaigns();
        return ApiResult.ok(new NotificationCampaignOverview(
                stats(campaigns),
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
        NotificationCampaignCreateRequest normalized = normalizeCreate(request);
        long estimatedAudience = campaignRepository.estimateAudience(normalized.audienceTarget(), currentPhase(), now());
        String campaignNo = nextCampaignNo(normalized.name());
        NotificationCampaignRow created = campaignRepository.createCampaign(campaignNo, normalized, estimatedAudience, now());
        audit("I3_NOTIFICATION_CAMPAIGN_CREATED", created.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "tier", created.tier(),
                "audience", created.audience(),
                "status", created.status()));
        return ApiResult.ok(created);
    }

    public ApiResult<NotificationCampaignRow> updateDraft(String campaignNo, String idempotencyKey, NotificationCampaignDraftRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireDraft(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        NotificationCampaignRow current = findCampaign(campaignNo);
        if (current == null) {
            return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
        }
        if (!"draft".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        NotificationCampaignDraftRequest normalized = normalizeDraft(request);
        long estimatedAudience = campaignRepository.estimateAudience(normalized.audienceTarget(), currentPhase(), now());
        campaignRepository.updateDraft(current.id(), normalized, estimatedAudience, now());
        NotificationCampaignRow updated = findCampaign(current.id());
        audit("I3_NOTIFICATION_CAMPAIGN_DRAFT_SAVED", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromStatus", current.status(),
                "toStatus", updated.status(),
                "tier", updated.tier()));
        return ApiResult.ok(updated);
    }

    public ApiResult<NotificationCampaignRow> scheduleCampaign(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        NotificationCampaignRow current = findCampaign(campaignNo);
        if (current == null) {
            return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
        }
        if (!"draft".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        ApiResult<NotificationCampaignRow> criticalGuard = requireCriticalExecutionAuthority(current);
        if (criticalGuard != null) {
            return criticalGuard;
        }
        if (!PHASES.contains(currentPhase())) {
            return ApiResult.fail(503, "NOTIFICATION_CURRENT_PHASE_UNAVAILABLE");
        }
        if (campaignRepository.estimateAudience(current.audienceTarget(), currentPhase(), now()) <= 0) {
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
        campaignRepository.updateStatus(current.id(), "SCHEDULED", schedule, request.operator(), now());
        NotificationCampaignRow updated = findCampaign(current.id());
        audit("I3_NOTIFICATION_CAMPAIGN_SCHEDULED", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of("schedule", updated.schedule()));
        return ApiResult.ok(updated);
    }

    public ApiResult<NotificationCampaignRow> sendNow(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        NotificationCampaignRow current = findCampaign(campaignNo);
        if (current == null) {
            return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
        }
        if (!Set.of("draft", "scheduled").contains(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        ApiResult<NotificationCampaignRow> criticalGuard = requireCriticalExecutionAuthority(current);
        if (criticalGuard != null) {
            return criticalGuard;
        }
        if (!PHASES.contains(currentPhase())) {
            return ApiResult.fail(503, "NOTIFICATION_CURRENT_PHASE_UNAVAILABLE");
        }
        LocalDateTime dispatchTime = now();
        try {
            dispatchExecutor.dispatchImmediate(
                    current.id(),
                    "i3:send:" + idempotencyKey.trim(),
                    currentPhase(),
                    request.operator(),
                    idempotencyKey.trim(),
                    request.reason().trim(),
                    dispatchTime);
        } catch (NotificationCampaignDispatchExecutor.AudienceEmptyException ex) {
            return ApiResult.fail(422, "AUDIENCE_EMPTY");
        } catch (NotificationCampaignDispatchExecutor.ConcurrentDispatchException ex) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        } catch (RuntimeException ex) {
            return ApiResult.fail(500, "NOTIFICATION_CAMPAIGN_DISPATCH_FAILED");
        }
        return ApiResult.ok(findCampaign(current.id()));
    }

    public ApiResult<NotificationCampaignRow> cancelScheduled(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        NotificationCampaignRow current = findCampaign(campaignNo);
        if (current == null) {
            return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
        }
        if (!"scheduled".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        ApiResult<NotificationCampaignRow> criticalGuard = requireCriticalExecutionAuthority(current);
        if (criticalGuard != null) {
            return criticalGuard;
        }
        if (!campaignRepository.cancelScheduled(current.id(), request.operator(), now())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        NotificationCampaignRow updated = findCampaign(current.id());
        audit("I3_NOTIFICATION_CAMPAIGN_CANCELLED", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of("fromStatus", current.status()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Void> deleteDraft(String campaignNo, String idempotencyKey, NotificationCampaignActionRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        NotificationCampaignRow current = findCampaign(campaignNo);
        if (current == null) {
            return ApiResult.fail(404, "NOTIFICATION_CAMPAIGN_NOT_FOUND");
        }
        if (!Set.of("draft", "cancelled").contains(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!campaignRepository.deleteDraft(current.id(), now())) {
            return ApiResult.fail(409, OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        audit("I3_NOTIFICATION_CAMPAIGN_DELETED", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromStatus", current.status()));
        return ApiResult.ok(null);
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

    public ApiResult<NotificationCapRuleView> updateCapRule(String tier, String idempotencyKey, NotificationCapUpdateRequest request) {
        String normalizedTier = normalizeTier(tier);
        if (!TIERS.contains(normalizedTier)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_TIER_UNSUPPORTED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.cap()) || request.cap().trim().length() > 32) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAP_INVALID");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
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
        if (request.cap().trim().equals(current.cap())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        campaignRepository.updateCapRule(normalizedTier, request.cap().trim(), request.operator(), now());
        campaignRepository.applyRetention(now());
        NotificationCapRuleView updated = campaignRepository.findCapRule(normalizedTier).orElseThrow();
        audit("I3_NOTIFICATION_CAP_CHANGED", normalizedTier, request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.cap(),
                "to", updated.cap()));
        return ApiResult.ok(updated);
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
        return null;
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
                trimToEmpty(request.ctaHref()));
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
                trimToEmpty(request.ctaHref()));
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

    private NotificationCampaignStats stats(List<NotificationCampaignRow> campaigns) {
        int sent = (int) campaigns.stream().filter(row -> "sent".equals(row.status())).count();
        int scheduled = (int) campaigns.stream().filter(row -> "scheduled".equals(row.status()) || "sending".equals(row.status())).count();
        int draft = (int) campaigns.stream().filter(row -> "draft".equals(row.status())).count();
        int criticalInflight = (int) campaigns.stream()
                .filter(row -> "critical".equals(row.tier()))
                .filter(row -> "scheduled".equals(row.status()) || "sending".equals(row.status()))
                .count();
        double delivered = campaigns.stream().filter(row -> "sent".equals(row.status())).mapToDouble(row -> quantity(row.sent())).sum();
        double read = campaigns.stream().filter(row -> "sent".equals(row.status())).mapToDouble(row -> quantity(row.read())).sum();
        return new NotificationCampaignStats(
                campaigns.size(),
                sent,
                scheduled,
                draft,
                criticalInflight,
                delivered <= 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", read * 100.0 / delivered),
                compactNumber(read * 0.031));
    }

    private NotificationAudienceCatalog audienceCatalog() {
        return new NotificationAudienceCatalog(
                PHASES.stream().map(value -> new NotificationAudienceOption(value, value)).toList(),
                List.of(
                        new NotificationAudienceOption("all", "全部语言"),
                        new NotificationAudienceOption("zh", "中文"),
                        new NotificationAudienceOption("vi", "越南语"),
                        new NotificationAudienceOption("en", "英文")),
                "AND");
    }

    private NotificationDeliveryCatalog deliveryCatalog() {
        return new NotificationDeliveryCatalog(
                List.of(
                        new NotificationAudienceOption("system", "系统通知"),
                        new NotificationAudienceOption("commission", "佣金通知"),
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
        if (!LANGUAGES.contains(target.language())) {
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

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private void audit(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(action.contains("CAP") ? "NOTIFICATION_CAP" : "NOTIFICATION_CAMPAIGN")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }
}
