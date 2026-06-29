package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NotificationCampaignOverview;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationCampaignStats;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
import ffdd.opsconsole.content.domain.NotificationSwipeRouteView;
import ffdd.opsconsole.content.dto.NotificationCampaignActionRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsNotificationCampaignService {
    private static final Set<String> TIERS = Set.of("critical", "high", "normal", "low");
    private static final Set<String> ACTIVE_STATUSES = Set.of("draft", "scheduled", "sending", "sent", "cancelled");
    private static final List<String> AUDIENCES = List.of(
            "全量",
            "SFC 辖区 · 未重确认用户",
            "近 30 天提现 >$1k",
            "注册 ≤14 天",
            "P3 阶段活跃用户");
    private static final List<NotificationSwipeRouteView> SWIPE_ROUTES = List.of(
            new NotificationSwipeRouteView("/reinvest", "commission", "佣金到账 -> 复投"),
            new NotificationSwipeRouteView("/me/bills", "refund", "退款到账 -> 账单"),
            new NotificationSwipeRouteView("-", "system", "维护公告 / KYC 提醒 / 监管通告 / 运营公告 - system kind 无转化跳转"));
    private static final DateTimeFormatter CAMPAIGN_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");

    private final NotificationCampaignRepository campaignRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public ApiResult<NotificationCampaignOverview> overview() {
        campaignRepository.ensureSeedData(now());
        List<NotificationCampaignRow> campaigns = campaignRepository.listCampaigns();
        return ApiResult.ok(new NotificationCampaignOverview(
                stats(campaigns),
                campaigns,
                campaignRepository.listCapRules(),
                List.copyOf(TIERS),
                AUDIENCES,
                List.copyOf(ACTIVE_STATUSES),
                SWIPE_ROUTES,
                List.of("nx_notification_campaign", "nx_notification_cap_rule", "nx_notification")));
    }

    public ApiResult<NotificationCampaignRow> createCampaign(String idempotencyKey, NotificationCampaignCreateRequest request) {
        ApiResult<NotificationCampaignRow> guard = requireCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String campaignNo = nextCampaignNo(request.name());
        NotificationCampaignRow created = campaignRepository.createCampaign(campaignNo, normalizeCreate(request), now());
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
        if ("sent".equals(current.status()) || "cancelled".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        campaignRepository.updateDraft(current.id(), normalizeDraft(request), now());
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
        String schedule = StringUtils.hasText(request.schedule()) ? request.schedule().trim() : "下一窗口排期";
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
        LocalDateTime now = now();
        campaignRepository.updateStatus(current.id(), "SENDING", "立即下发中", request.operator(), now);
        int notificationCount = campaignRepository.dispatchCampaignNotification(
                current.id(),
                "i3:send:" + idempotencyKey.trim(),
                "立即下发中",
                request.operator(),
                now);
        NotificationCampaignRow updated = findCampaign(current.id());
        audit("I3_NOTIFICATION_CAMPAIGN_SEND_NOW", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromStatus", current.status(),
                "notificationCount", notificationCount));
        return ApiResult.ok(updated);
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
        campaignRepository.updateStatus(current.id(), "CANCELLED", "已取消", request.operator(), now());
        NotificationCampaignRow updated = findCampaign(current.id());
        audit("I3_NOTIFICATION_CAMPAIGN_CANCELLED", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of("fromStatus", current.status()));
        return ApiResult.ok(updated);
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
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
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
        if (request == null || !StringUtils.hasText(request.name()) || !StringUtils.hasText(request.title()) || !StringUtils.hasText(request.content())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_FIELDS_REQUIRED");
        }
        if (request.name().trim().length() > 160 || request.title().trim().length() > 160 || request.content().trim().length() > 4000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_TEXT_TOO_LONG");
        }
        if (JSON_LIKE_PATTERN.matcher(request.content()).find()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_JSON_NOT_ALLOWED");
        }
        if (!TIERS.contains(normalizeTier(request.tier()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_TIER_UNSUPPORTED");
        }
        if (!StringUtils.hasText(request.audience())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_AUDIENCE_REQUIRED");
        }
        if (request.budget() != null && request.budget().compareTo(BigDecimal.ZERO) < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_BUDGET_INVALID");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<NotificationCampaignRow> requireDraft(String idempotencyKey, NotificationCampaignDraftRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.title()) || !StringUtils.hasText(request.body())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_DRAFT_REQUIRED");
        }
        if (!TIERS.contains(normalizeTier(request.tier()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_TIER_UNSUPPORTED");
        }
        if (!StringUtils.hasText(request.audience())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_AUDIENCE_REQUIRED");
        }
        if (request.budget() != null && request.budget().compareTo(BigDecimal.ZERO) < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_BUDGET_INVALID");
        }
        if (JSON_LIKE_PATTERN.matcher(request.body()).find()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOTIFICATION_CAMPAIGN_JSON_NOT_ALLOWED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<NotificationCampaignRow> requireAction(String idempotencyKey, NotificationCampaignActionRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private NotificationCampaignCreateRequest normalizeCreate(NotificationCampaignCreateRequest request) {
        return new NotificationCampaignCreateRequest(
                request.name().trim(),
                request.title().trim(),
                request.content().trim(),
                normalizeTier(request.tier()),
                request.audience().trim(),
                request.budget(),
                operator(request.operator()),
                request.reason().trim());
    }

    private NotificationCampaignDraftRequest normalizeDraft(NotificationCampaignDraftRequest request) {
        return new NotificationCampaignDraftRequest(
                request.title().trim(),
                request.body().trim(),
                normalizeTier(request.tier()),
                request.audience().trim(),
                StringUtils.hasText(request.schedule()) ? request.schedule().trim() : "-",
                request.budget(),
                operator(request.operator()),
                request.reason().trim());
    }

    private NotificationCampaignRow findCampaign(String campaignNo) {
        return StringUtils.hasText(campaignNo) ? campaignRepository.findCampaign(campaignNo.trim()).orElse(null) : null;
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
        return StringUtils.hasText(normalized) ? normalized : "campaign";
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
