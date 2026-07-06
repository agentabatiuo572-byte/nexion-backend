package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaEventDrivenView;
import ffdd.opsconsole.content.domain.NovaOverview;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialPoolView;
import ffdd.opsconsole.content.domain.NovaStats;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.dto.NovaChannelStatusRequest;
import ffdd.opsconsole.content.dto.NovaChannelUpsertRequest;
import ffdd.opsconsole.content.dto.NovaDeleteRequest;
import ffdd.opsconsole.content.dto.NovaDistributionUpdateRequest;
import ffdd.opsconsole.content.dto.NovaPoolUpdateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateCreateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsNovaService {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");
    private static final Set<String> TEMPLATE_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final Map<String, DistributionOption> DISTRIBUTION_OPTIONS = List.of(
                    new DistributionOption("withdrawal", "提现到账", "var(--admin-cat-3)"),
                    new DistributionOption("vrank", "V 等级晋升", "var(--admin-cat-5)"),
                    new DistributionOption("genesis", "Genesis 成交", "var(--admin-cat-7)"),
                    new DistributionOption("aiClient", "AI 客户消费", "var(--admin-cat-2)"),
                    new DistributionOption("newUsers", "每小时新增用户", "var(--admin-cat-4)"))
            .stream()
            .collect(Collectors.toMap(DistributionOption::key, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    private static final Map<String, PoolOption> POOL_OPTIONS = List.of(
                    new PoolOption("SOCIAL_NAMES", "人名池", "事件里出现的化名,按市场轮换"),
                    new PoolOption("CITIES", "城市池", "事件发生地,按市场轮换"),
                    new PoolOption("AI_CLIENTS", "AI 客户池", "AI 客户消费事件的客户名单"))
            .stream()
            .collect(Collectors.toMap(PoolOption::key, Function.identity(), (left, right) -> left, LinkedHashMap::new));

    // 事件驱动频道固定目录(DB 空→不可调事件流)。
    private static final List<NovaEventDrivenView> EVENT_DRIVEN_SEEDS = List.of(
            new NovaEventDrivenView("首充到账推送", "用户首次充值成功后即时触发", "growth@nexion", "success", "active"),
            new NovaEventDrivenView("Genesis 成交通知", "节点成交后广播动态", "genesis@nexion", "highlight", "active"),
            new NovaEventDrivenView("提现到账提醒", "提现完成推送到账", "risk@nexion", "info", "active"),
            new NovaEventDrivenView("V 等级晋升", "用户 V 等级提升时推送", "growth@nexion", "highlight", "active"),
            new NovaEventDrivenView("AI 客户消费", "AI 客户消费事件展示", "ops@nexion", "info", "active"));

    private final NovaRepository novaRepository;
    private final AuditLogService auditLogService;

    public ApiResult<NovaOverview> overview() {
        List<NovaChannelView> channels = novaRepository.channels();
        Map<String, Object> stats = novaRepository.stats();
        return ApiResult.ok(new NovaOverview(
                novaStats(stats),
                channels,
                EVENT_DRIVEN_SEEDS,
                novaRepository.templates(),
                mergeSocialDistribution(novaRepository.socialDistribution()),
                novaRepository.socialPools(),
                List.copyOf(TEMPLATE_STATUSES),
                List.of(
                        "nx_nova_channel",
                        "nx_nova_template",
                        "nx_nova_social_distribution",
                        "nx_nova_social_pool",
                        "nx_notification")));
    }

    private List<NovaSocialDistributionItem> mergeSocialDistribution(List<NovaSocialDistributionItem> dbItems) {
        // DB 社交分布优先;DB 未覆盖的渠道用 DISTRIBUTION_OPTIONS 兜底,DB 全空时均分 100%。
        java.util.Set<String> dbKeys = dbItems.stream()
                .map(NovaSocialDistributionItem::key)
                .collect(java.util.stream.Collectors.toSet());
        List<NovaSocialDistributionItem> merged = new java.util.ArrayList<>(dbItems);
        int fallbackPct = dbItems.isEmpty() ? 100 / Math.max(1, DISTRIBUTION_OPTIONS.size()) : 0;
        DISTRIBUTION_OPTIONS.values().stream()
                .filter(option -> !dbKeys.contains(option.key()))
                .forEach(option -> merged.add(new NovaSocialDistributionItem(option.key(), option.name(), fallbackPct, option.color())));
        return merged;
    }

    public ApiResult<NovaChannelView> createChannel(String idempotencyKey, NovaChannelUpsertRequest request) {
        ApiResult<NovaChannelView> guard = requireChannelCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        novaRepository.ensureTables();
        String key = normalizeChannelKey(StringUtils.hasText(request.key()) ? request.key() : slug(request.name()));
        if (novaRepository.channel(key).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_CHANNEL_EXISTS");
        }
        novaRepository.createChannel(
                key,
                request.name().trim(),
                request.trigger().trim(),
                request.tick().trim(),
                request.cooldown().trim(),
                safeCtr(request.ctr()),
                request.enabled() == null || request.enabled(),
                novaRepository.nextChannelOrder(),
                request.operator(),
                request.reason().trim());
        NovaChannelView created = novaRepository.channel(key).orElseThrow();
        audit("I2_NOVA_CHANNEL_CREATED", key, request.operator(), idempotencyKey, request.reason(), Map.of(
                "enabled", created.enabled(),
                "tick", created.tick(),
                "cooldown", created.cooldown()));
        return ApiResult.ok(created);
    }

    public ApiResult<NovaChannelView> updateChannel(String key, String idempotencyKey, NovaChannelUpsertRequest request) {
        String normalizedKey = normalizeChannelKey(key);
        ApiResult<NovaChannelView> guard = requireChannelCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        novaRepository.ensureTables();
        Optional<NovaChannelView> current = novaRepository.channel(normalizedKey);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_CHANNEL_NOT_FOUND");
        }
        boolean enabled = request.enabled() == null ? current.get().enabled() : request.enabled();
        novaRepository.updateChannel(
                normalizedKey,
                request.name().trim(),
                request.trigger().trim(),
                request.tick().trim(),
                request.cooldown().trim(),
                safeCtr(request.ctr()),
                enabled,
                request.operator(),
                request.reason().trim());
        NovaChannelView updated = novaRepository.channel(normalizedKey).orElseThrow();
        audit("I2_NOVA_CHANNEL_UPDATED", normalizedKey, request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromCtr", current.get().ctr(),
                "toCtr", updated.ctr(),
                "enabled", updated.enabled()));
        return ApiResult.ok(updated);
    }

    public ApiResult<NovaChannelView> updateChannelStatus(String key, String idempotencyKey, NovaChannelStatusRequest request) {
        String normalizedKey = normalizeChannelKey(key);
        ApiResult<NovaChannelView> guard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.enabled() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_CHANNEL_ENABLED_REQUIRED");
        }
        novaRepository.ensureTables();
        Optional<NovaChannelView> current = novaRepository.channel(normalizedKey);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_CHANNEL_NOT_FOUND");
        }
        if (current.get().enabled() == request.enabled()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        novaRepository.updateChannelStatus(normalizedKey, request.enabled(), request.operator(), request.reason().trim());
        NovaChannelView updated = novaRepository.channel(normalizedKey).orElseThrow();
        audit(request.enabled() ? "I2_NOVA_CHANNEL_RESTORED" : "I2_NOVA_CHANNEL_KILLED", normalizedKey,
                request.operator(), idempotencyKey, request.reason(), Map.of("enabled", request.enabled()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Void> deleteChannel(String key, String idempotencyKey, NovaDeleteRequest request) {
        String normalizedKey = normalizeChannelKey(key);
        ApiResult<Void> guard = requireVoidReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        novaRepository.ensureTables();
        if (novaRepository.channel(normalizedKey).isEmpty()) {
            return ApiResult.fail(404, "NOVA_CHANNEL_NOT_FOUND");
        }
        novaRepository.deleteChannel(normalizedKey, request.operator(), request.reason().trim());
        audit("I2_NOVA_CHANNEL_DELETED", normalizedKey, request.operator(), idempotencyKey, request.reason(), Map.of("deleted", true));
        return ApiResult.ok();
    }

    public ApiResult<NovaTemplateView> createTemplate(String idempotencyKey, NovaTemplateCreateRequest request) {
        ApiResult<NovaTemplateView> guard = requireTemplateCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        novaRepository.ensureTables();
        String channel = normalizeChannelKey(request.channel());
        if (novaRepository.template(channel).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_TEMPLATE_EXISTS");
        }
        novaRepository.createTemplate(
                channel,
                request.name().trim(),
                request.cta().trim(),
                request.version().trim(),
                request.operator(),
                request.reason().trim());
        NovaTemplateView created = novaRepository.template(channel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_CREATED", channel, request.operator(), idempotencyKey, request.reason(), Map.of("status", "DRAFT"));
        return ApiResult.ok(created);
    }

    public ApiResult<NovaTemplateView> updateTemplateStatus(String channel, String idempotencyKey, NovaTemplateStatusRequest request) {
        String normalizedChannel = normalizeChannelKey(channel);
        ApiResult<NovaTemplateView> guard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String status = normalizeStatus(request.status());
        if (!TEMPLATE_STATUSES.contains(status)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_STATUS_UNSUPPORTED");
        }
        novaRepository.ensureTables();
        Optional<NovaTemplateView> current = novaRepository.template(normalizedChannel);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_TEMPLATE_NOT_FOUND");
        }
        if (status.equals(current.get().status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        novaRepository.updateTemplateStatus(normalizedChannel, status, request.operator(), request.reason().trim());
        NovaTemplateView updated = novaRepository.template(normalizedChannel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_STATUS_CHANGED", normalizedChannel, request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.get().status(),
                "to", status));
        return ApiResult.ok(updated);
    }

    public ApiResult<List<NovaSocialDistributionItem>> updateDistribution(String idempotencyKey, NovaDistributionUpdateRequest request) {
        ApiResult<List<NovaSocialDistributionItem>> guard = requireDistributionCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        Map<String, Integer> next = request.items().stream()
                .collect(Collectors.toMap(
                        item -> normalizeDistributionKey(item.key()),
                        NovaDistributionUpdateRequest.Item::pct,
                        (left, right) -> right,
                        LinkedHashMap::new));
        if (!DISTRIBUTION_OPTIONS.keySet().equals(next.keySet())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_DISTRIBUTION_KEYS_REQUIRED");
        }
        int total = next.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_DISTRIBUTION_TOTAL_MUST_BE_100");
        }
        novaRepository.ensureTables();
        next.forEach((key, value) -> {
            DistributionOption option = DISTRIBUTION_OPTIONS.get(key);
            novaRepository.upsertDistribution(key, option.name(), value, option.color(), request.operator(), request.reason().trim());
        });
        List<NovaSocialDistributionItem> updated = novaRepository.socialDistribution();
        audit("I2_NOVA_SOCIAL_DISTRIBUTION_CHANGED", "social", request.operator(), idempotencyKey, request.reason(), Map.of("total", total));
        return ApiResult.ok(updated);
    }

    public ApiResult<NovaSocialPoolView> updatePool(String poolKey, String idempotencyKey, NovaPoolUpdateRequest request) {
        String normalizedPoolKey = normalizePoolKey(poolKey);
        ApiResult<NovaSocialPoolView> guard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.count() == null || request.count() < 0 || request.count() > 10000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_POOL_COUNT_INVALID");
        }
        novaRepository.ensureTables();
        PoolOption option = POOL_OPTIONS.get(normalizedPoolKey);
        novaRepository.upsertPool(option.key(), option.name(), option.description(), request.count(), request.operator(), request.reason().trim());
        NovaSocialPoolView updated = novaRepository.socialPool(option.key()).orElseThrow();
        audit("I2_NOVA_SOCIAL_POOL_CHANGED", normalizedPoolKey, request.operator(), idempotencyKey, request.reason(), Map.of("count", request.count()));
        return ApiResult.ok(updated);
    }

    private NovaStats novaStats(Map<String, Object> stats) {
        Map<String, Object> row = stats == null ? Map.of() : stats;
        return new NovaStats(
                formatLong(row.get("todayDelivered")),
                formatPercent(decimalValue(row.get("avgCtr"))),
                intValue(row.get("ctrTarget")),
                intValue(row.get("onlineChannels")),
                intValue(row.get("totalChannels")),
                formatLong(row.get("weeklySocial")));
    }

    private String formatPercent(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private String formatLong(Object value) {
        return String.valueOf(longValue(value));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return new BigDecimal(text.trim()).longValue();
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private int intValue(Object value) {
        long parsed = longValue(value);
        if (parsed > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (parsed < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) parsed;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number || value instanceof String) {
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private ApiResult<NovaChannelView> requireChannelCommand(String idempotencyKey, NovaChannelUpsertRequest request) {
        ApiResult<NovaChannelView> reasonGuard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (reasonGuard != null) {
            return reasonGuard;
        }
        if (!StringUtils.hasText(request.name()) || !StringUtils.hasText(request.trigger())
                || !StringUtils.hasText(request.tick()) || !StringUtils.hasText(request.cooldown())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_CHANNEL_FIELDS_REQUIRED");
        }
        if (request.ctr() != null && (request.ctr().compareTo(BigDecimal.ZERO) < 0 || request.ctr().compareTo(new BigDecimal("100")) > 0)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_CHANNEL_CTR_INVALID");
        }
        return null;
    }

    private ApiResult<NovaTemplateView> requireTemplateCreate(String idempotencyKey, NovaTemplateCreateRequest request) {
        ApiResult<NovaTemplateView> reasonGuard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (reasonGuard != null) {
            return reasonGuard;
        }
        if (!StringUtils.hasText(request.channel()) || !StringUtils.hasText(request.name())
                || !StringUtils.hasText(request.cta()) || !StringUtils.hasText(request.version())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_FIELDS_REQUIRED");
        }
        return null;
    }

    private ApiResult<List<NovaSocialDistributionItem>> requireDistributionCommand(String idempotencyKey, NovaDistributionUpdateRequest request) {
        ApiResult<List<NovaSocialDistributionItem>> reasonGuard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (reasonGuard != null) {
            return reasonGuard;
        }
        if (request.items() == null || request.items().isEmpty()
                || request.items().stream().anyMatch(item -> item == null || item.pct() == null || item.pct() < 0 || item.pct() > 100)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_DISTRIBUTION_INVALID");
        }
        if (request.items().stream().anyMatch(item -> !isDistributionKey(item.key()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_DISTRIBUTION_KEY_INVALID");
        }
        return null;
    }

    private <T> ApiResult<T> requireReason(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<Void> requireVoidReason(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private BigDecimal safeCtr(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeChannelKey(String key) {
        if (!StringUtils.hasText(key) || !KEY_PATTERN.matcher(key.trim()).matches()) {
            throw new IllegalArgumentException("NOVA_KEY_INVALID");
        }
        return key.trim();
    }

    private String normalizeDistributionKey(String key) {
        String normalized = normalizeChannelKey(key);
        if (!DISTRIBUTION_OPTIONS.containsKey(normalized)) {
            throw new IllegalArgumentException("NOVA_SOCIAL_DISTRIBUTION_KEY_INVALID");
        }
        return normalized;
    }

    private boolean isDistributionKey(String key) {
        return StringUtils.hasText(key)
                && KEY_PATTERN.matcher(key.trim()).matches()
                && DISTRIBUTION_OPTIONS.containsKey(key.trim());
    }

    private String normalizePoolKey(String key) {
        String normalized = normalizeChannelKey(key).toUpperCase(Locale.ROOT);
        if (!POOL_OPTIONS.containsKey(normalized)) {
            throw new IllegalArgumentException("NOVA_SOCIAL_POOL_UNSUPPORTED");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "DRAFT";
    }

    private String slug(String value) {
        String raw = StringUtils.hasText(value) ? value.trim() : "nova-channel";
        String slug = raw.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return StringUtils.hasText(slug) ? slug : "nova-channel";
    }

    private void audit(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> detail) {
        Map<String, Object> fullDetail = new LinkedHashMap<>(detail == null ? Map.of() : detail);
        fullDetail.put("idempotencyKey", idempotencyKey);
        fullDetail.put("reason", reason);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("NOVA")
                .resourceId(resourceId)
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .detail(fullDetail)
                .build());
    }

    private record DistributionOption(String key, String name, String color) {
    }

    private record PoolOption(String key, String name, String description) {
    }
}
