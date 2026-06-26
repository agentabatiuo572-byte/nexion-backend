package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaEventDrivenView;
import ffdd.opsconsole.content.domain.NovaOverview;
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
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsNovaService {
    private static final String GROUP = "content_nova";
    private static final String CHANNEL_PREFIX = "nova.channel.";
    private static final String TEMPLATE_PREFIX = "nova.template.";
    private static final String SOCIAL_DIST_PREFIX = "nova.social.dist.";
    private static final String SOCIAL_POOL_PREFIX = "nova.social.pool.";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");
    private static final Set<String> TEMPLATE_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private static final List<ChannelSeed> CHANNEL_SEEDS = List.of(
            channel("welcome", "首推 · 玩法解释", "注册后首推 · 玩法解释", "注册 8s", "30d", "-", "31.2", true, 10),
            channel("market", "行情波动播报", "全网算力波动 / NEX 价播报", "12 min", "60 min", "-", "29.1", true, 20),
            channel("upgrade", "升级推荐 · 按机队", "按持有机队推荐升级", "每日 11:00", "7d", "-", "21.8", true, 30),
            channel("dailySummary", "任务日报", "每完成 25 个任务推一次日报", "每 25 任务", "20h", "-", "26.3", true, 40),
            channel("tradein", "以旧换新钩子", "Trade-in 升级钩子", "每日 10:00", "24h", "P1-2 不推 · P5-6 歇 24h", "24.9", true, 50),
            channel("social", "全网真实事件 · social", "5 类真实事件按概率派发", "8 min", "90 min", "-", "28.7", true, 60),
            channel("eventClaim", "催领提醒 · 活动奖励", "有可领取活动奖励时催领", "30 min", "12h", "-", "33.4", true, 70),
            channel("wrapped", "半年/年度 Wrapped 回顾", "半年/年度 Wrapped 回顾召回", "一次性", "180d", "-", "35.6", true, 80),
            channel("taskLockMonthly", "月度任务锁定召回", "月度任务累计召回", "每日 09:00", "3.5d", "P1-2 歇 30d · P5-6 歇 3.5d", "18.5", true, 90),
            channel("quest", "首日任务宽限 / 过期召回", "首日任务宽限 / 过期召回", "6h", "24h", "-", "22.7", true, 100));

    private static final List<NovaEventDrivenView> EVENT_DRIVEN = List.of(
            new NovaEventDrivenView("risk-alert", "设备掉线 / 任务失败时事件触发,没有多久推一次的概念", "异常事件状态机驱动", "dim", "事件触发 · 仅模板可管"),
            new NovaEventDrivenView("weekly-quest-refresh", "每周任务刷新时触发,节奏跟任务系统走", "任务系统(H3)", "dim", "事件触发 · 仅模板可管"),
            new NovaEventDrivenView("team_event / staking_event / market_event", "v3 业务频道待整合,落地前 I2 不持有调频项", "App 硬编码整合工单", "warn", "待整合工单"));

    private static final List<TemplateSeed> TEMPLATE_SEEDS = List.of(
            template("welcome", "首推 · 玩法解释", "→ /onboarding", "v3", "PUBLISHED"),
            template("market", "行情波动播报", "→ /market", "v2", "PUBLISHED"),
            template("upgrade", "升级推荐 · 按机队", "→ /shop(E)", "v4", "PUBLISHED"),
            template("tradein", "以旧换新钩子", "→ /tradein(E)", "v2", "PUBLISHED"),
            template("social", "全网真实事件 · social", "—(无 CTA)", "v5", "PUBLISHED"),
            template("eventClaim", "催领提醒 · 活动奖励", "→ /events(H4)", "v3", "PUBLISHED"),
            template("wrapped", "半年/年度 Wrapped 回顾", "→ /me/wrapped", "v1", "PUBLISHED"));

    private static final List<DistributionSeed> DISTRIBUTION_SEEDS = List.of(
            distribution("withdrawal", "提现到账", 30, "var(--admin-cat-3)"),
            distribution("vrank", "V 等级晋升", 25, "var(--admin-cat-5)"),
            distribution("genesis", "Genesis 成交", 20, "var(--admin-cat-7)"),
            distribution("aiClient", "AI 客户消费", 15, "var(--admin-cat-2)"),
            distribution("newUsers", "每小时新增用户", 10, "var(--admin-cat-4)"));

    private static final List<PoolSeed> POOL_SEEDS = List.of(
            pool("SOCIAL_NAMES", "人名池", "事件里出现的化名,按市场轮换", 48),
            pool("CITIES", "城市池", "事件发生地,按市场轮换", 32),
            pool("AI_CLIENTS", "AI 客户池", "AI 客户消费事件的客户名单", 12));

    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;

    public ApiResult<NovaOverview> overview() {
        ensureSeedConfigs();
        Map<String, String> configs = configs();
        List<NovaChannelView> channels = channels(configs);
        int onlineCount = (int) channels.stream().filter(NovaChannelView::enabled).count();
        return ApiResult.ok(new NovaOverview(
                new NovaStats("84.2K", "27.4%", 25, onlineCount, channels.size(), "12.8K"),
                channels,
                EVENT_DRIVEN,
                templates(configs),
                distribution(configs),
                pools(configs),
                List.copyOf(TEMPLATE_STATUSES),
                List.of("nx_config_item")));
    }

    public ApiResult<NovaChannelView> createChannel(String idempotencyKey, NovaChannelUpsertRequest request) {
        ApiResult<NovaChannelView> guard = requireChannelCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        Map<String, String> configs = configs();
        String key = normalizeChannelKey(StringUtils.hasText(request.key()) ? request.key() : slug(request.name()));
        if (findChannel(configs, key).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_CHANNEL_EXISTS");
        }
        saveChannel(key, request, true, request.reason());
        NovaChannelView created = findChannel(configs(), key).orElseThrow();
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
        Map<String, String> configs = configs();
        Optional<NovaChannelView> current = findChannel(configs, normalizedKey);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_CHANNEL_NOT_FOUND");
        }
        saveChannel(normalizedKey, request, request.enabled() == null ? current.get().enabled() : request.enabled(), request.reason());
        NovaChannelView updated = findChannel(configs(), normalizedKey).orElseThrow();
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
        Map<String, String> configs = configs();
        Optional<NovaChannelView> current = findChannel(configs, normalizedKey);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_CHANNEL_NOT_FOUND");
        }
        if (current.get().enabled() == request.enabled()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        put(CHANNEL_PREFIX + normalizedKey + ".on", String.valueOf(request.enabled()), request.reason());
        NovaChannelView updated = findChannel(configs(), normalizedKey).orElseThrow();
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
        if (findChannel(configs(), normalizedKey).isEmpty()) {
            return ApiResult.fail(404, "NOVA_CHANNEL_NOT_FOUND");
        }
        put(CHANNEL_PREFIX + normalizedKey + ".deleted", "true", request.reason());
        audit("I2_NOVA_CHANNEL_DELETED", normalizedKey, request.operator(), idempotencyKey, request.reason(), Map.of("deleted", true));
        return ApiResult.ok();
    }

    public ApiResult<NovaTemplateView> createTemplate(String idempotencyKey, NovaTemplateCreateRequest request) {
        ApiResult<NovaTemplateView> guard = requireTemplateCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String channel = normalizeChannelKey(request.channel());
        if (findTemplate(configs(), channel).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_TEMPLATE_EXISTS");
        }
        put(TEMPLATE_PREFIX + channel + ".name", request.name().trim(), request.reason());
        put(TEMPLATE_PREFIX + channel + ".cta", request.cta().trim(), request.reason());
        put(TEMPLATE_PREFIX + channel + ".version", request.version().trim(), request.reason());
        put(TEMPLATE_PREFIX + channel + ".status", "DRAFT", request.reason());
        NovaTemplateView created = findTemplate(configs(), channel).orElseThrow();
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
        Optional<NovaTemplateView> current = findTemplate(configs(), normalizedChannel);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_TEMPLATE_NOT_FOUND");
        }
        if (status.equals(current.get().status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        put(TEMPLATE_PREFIX + normalizedChannel + ".status", status, request.reason());
        NovaTemplateView updated = findTemplate(configs(), normalizedChannel).orElseThrow();
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
        Set<String> allowed = DISTRIBUTION_SEEDS.stream().map(DistributionSeed::key).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!allowed.equals(next.keySet())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_DISTRIBUTION_KEYS_REQUIRED");
        }
        int total = next.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_DISTRIBUTION_TOTAL_MUST_BE_100");
        }
        next.forEach((key, value) -> put(SOCIAL_DIST_PREFIX + key + ".pct", String.valueOf(value), request.reason()));
        List<NovaSocialDistributionItem> updated = distribution(configs());
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
        if (POOL_SEEDS.stream().noneMatch(pool -> pool.key().equals(normalizedPoolKey))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_POOL_UNSUPPORTED");
        }
        put(SOCIAL_POOL_PREFIX + normalizedPoolKey + ".count", String.valueOf(request.count()), request.reason());
        NovaSocialPoolView updated = pools(configs()).stream()
                .filter(pool -> pool.key().equals(normalizedPoolKey))
                .findFirst()
                .orElseThrow();
        audit("I2_NOVA_SOCIAL_POOL_CHANGED", normalizedPoolKey, request.operator(), idempotencyKey, request.reason(), Map.of("count", request.count()));
        return ApiResult.ok(updated);
    }

    private List<NovaChannelView> channels(Map<String, String> configs) {
        List<NovaChannelView> channels = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChannelSeed seed : CHANNEL_SEEDS) {
            seen.add(seed.key());
            if (!bool(configs, CHANNEL_PREFIX + seed.key() + ".deleted", false)) {
                channels.add(toChannel(seed.key(), seed, configs));
            }
        }
        for (String key : discoveredKeys(configs, CHANNEL_PREFIX)) {
            if (!seen.contains(key) && !bool(configs, CHANNEL_PREFIX + key + ".deleted", false)) {
                channels.add(toChannel(key, null, configs));
            }
        }
        channels.sort(Comparator.comparingInt(channel -> number(configs, CHANNEL_PREFIX + channel.key() + ".order", orderFor(channel.key()))));
        return List.copyOf(channels);
    }

    private Optional<NovaChannelView> findChannel(Map<String, String> configs, String key) {
        return channels(configs).stream()
                .filter(channel -> channel.key().equals(key))
                .findFirst();
    }

    private NovaChannelView toChannel(String key, ChannelSeed seed, Map<String, String> configs) {
        return new NovaChannelView(
                key,
                text(configs, CHANNEL_PREFIX + key + ".name", seed == null ? key : seed.name()),
                text(configs, CHANNEL_PREFIX + key + ".trigger", seed == null ? "后台新增 Nova 通道" : seed.trigger()),
                text(configs, CHANNEL_PREFIX + key + ".tick", seed == null ? "-" : seed.tick()),
                text(configs, CHANNEL_PREFIX + key + ".cooldown", seed == null ? "-" : seed.cooldown()),
                text(configs, CHANNEL_PREFIX + key + ".phaseKeyed", seed == null ? "-" : seed.phaseKeyed()),
                decimal(configs, CHANNEL_PREFIX + key + ".ctr", seed == null ? BigDecimal.ZERO : seed.ctr()),
                bool(configs, CHANNEL_PREFIX + key + ".on", seed == null || seed.enabled()));
    }

    private List<NovaTemplateView> templates(Map<String, String> configs) {
        List<NovaTemplateView> templates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TemplateSeed seed : TEMPLATE_SEEDS) {
            seen.add(seed.channel());
            if (!bool(configs, TEMPLATE_PREFIX + seed.channel() + ".deleted", false)) {
                templates.add(toTemplate(seed.channel(), seed, configs));
            }
        }
        for (String channel : discoveredKeys(configs, TEMPLATE_PREFIX)) {
            if (!seen.contains(channel) && !bool(configs, TEMPLATE_PREFIX + channel + ".deleted", false)) {
                templates.add(toTemplate(channel, null, configs));
            }
        }
        return List.copyOf(templates);
    }

    private Optional<NovaTemplateView> findTemplate(Map<String, String> configs, String channel) {
        return templates(configs).stream()
                .filter(template -> template.channel().equals(channel))
                .findFirst();
    }

    private NovaTemplateView toTemplate(String channel, TemplateSeed seed, Map<String, String> configs) {
        return new NovaTemplateView(
                channel,
                text(configs, TEMPLATE_PREFIX + channel + ".name", seed == null ? channel : seed.name()),
                text(configs, TEMPLATE_PREFIX + channel + ".cta", seed == null ? "-" : seed.cta()),
                text(configs, TEMPLATE_PREFIX + channel + ".version", seed == null ? "v1" : seed.version()),
                normalizeStatus(text(configs, TEMPLATE_PREFIX + channel + ".status", seed == null ? "DRAFT" : seed.status())));
    }

    private List<NovaSocialDistributionItem> distribution(Map<String, String> configs) {
        return DISTRIBUTION_SEEDS.stream()
                .map(seed -> new NovaSocialDistributionItem(
                        seed.key(),
                        seed.name(),
                        number(configs, SOCIAL_DIST_PREFIX + seed.key() + ".pct", seed.pct()),
                        seed.color()))
                .toList();
    }

    private List<NovaSocialPoolView> pools(Map<String, String> configs) {
        return POOL_SEEDS.stream()
                .map(seed -> new NovaSocialPoolView(
                        seed.key(),
                        seed.name(),
                        seed.description(),
                        number(configs, SOCIAL_POOL_PREFIX + seed.key() + ".count", seed.count())))
                .toList();
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

    private void saveChannel(String key, NovaChannelUpsertRequest request, boolean enabled, String reason) {
        put(CHANNEL_PREFIX + key + ".name", request.name().trim(), reason);
        put(CHANNEL_PREFIX + key + ".trigger", request.trigger().trim(), reason);
        put(CHANNEL_PREFIX + key + ".tick", request.tick().trim(), reason);
        put(CHANNEL_PREFIX + key + ".cooldown", request.cooldown().trim(), reason);
        put(CHANNEL_PREFIX + key + ".ctr", decimalValue(request.ctr()), reason);
        put(CHANNEL_PREFIX + key + ".on", String.valueOf(enabled), reason);
        put(CHANNEL_PREFIX + key + ".deleted", "false", reason);
        if (configFacade.activeValue(CHANNEL_PREFIX + key + ".order").isEmpty()) {
            put(CHANNEL_PREFIX + key + ".order", String.valueOf(1000 + discoveredKeys(configs(), CHANNEL_PREFIX).size()), reason);
        }
    }

    private void ensureSeedConfigs() {
        for (ChannelSeed seed : CHANNEL_SEEDS) {
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".name", seed.name());
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".trigger", seed.trigger());
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".tick", seed.tick());
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".cooldown", seed.cooldown());
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".phaseKeyed", seed.phaseKeyed());
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".ctr", seed.ctr().stripTrailingZeros().toPlainString());
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".on", String.valueOf(seed.enabled()));
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".deleted", "false");
            seedIfMissing(CHANNEL_PREFIX + seed.key() + ".order", String.valueOf(seed.order()));
        }
        for (TemplateSeed seed : TEMPLATE_SEEDS) {
            seedIfMissing(TEMPLATE_PREFIX + seed.channel() + ".name", seed.name());
            seedIfMissing(TEMPLATE_PREFIX + seed.channel() + ".cta", seed.cta());
            seedIfMissing(TEMPLATE_PREFIX + seed.channel() + ".version", seed.version());
            seedIfMissing(TEMPLATE_PREFIX + seed.channel() + ".status", seed.status());
            seedIfMissing(TEMPLATE_PREFIX + seed.channel() + ".deleted", "false");
        }
        for (DistributionSeed seed : DISTRIBUTION_SEEDS) {
            seedIfMissing(SOCIAL_DIST_PREFIX + seed.key() + ".pct", String.valueOf(seed.pct()));
        }
        for (PoolSeed seed : POOL_SEEDS) {
            seedIfMissing(SOCIAL_POOL_PREFIX + seed.key() + ".count", String.valueOf(seed.count()));
        }
    }

    private void seedIfMissing(String key, String value) {
        if (configFacade.activeValue(key).isEmpty()) {
            configFacade.upsertAdminValue(key, value, "STRING", GROUP, "I2 Nova seed");
        }
    }

    private Map<String, String> configs() {
        return new LinkedHashMap<>(configFacade.activeValuesByGroup(GROUP));
    }

    private void put(String key, String value, String reason) {
        configFacade.upsertAdminValue(key, value, "STRING", GROUP, "I2 Nova: " + reason.trim());
    }

    private List<String> discoveredKeys(Map<String, String> configs, String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        for (String configKey : configs.keySet()) {
            if (configKey.startsWith(prefix)) {
                String rest = configKey.substring(prefix.length());
                int fieldIndex = rest.lastIndexOf('.');
                if (fieldIndex > 0) {
                    keys.add(rest.substring(0, fieldIndex));
                }
            }
        }
        return List.copyOf(keys);
    }

    private String text(Map<String, String> configs, String key, String fallback) {
        String value = configs.get(key);
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private int number(Map<String, String> configs, String key, int fallback) {
        try {
            return Integer.parseInt(text(configs, key, String.valueOf(fallback)).replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private BigDecimal decimal(Map<String, String> configs, String key, BigDecimal fallback) {
        try {
            return new BigDecimal(text(configs, key, fallback.toPlainString()));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean bool(Map<String, String> configs, String key, boolean fallback) {
        String value = configs.get(key);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private String decimalValue(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private int orderFor(String key) {
        return CHANNEL_SEEDS.stream()
                .filter(seed -> seed.key().equals(key))
                .findFirst()
                .map(ChannelSeed::order)
                .orElse(1000);
    }

    private String normalizeChannelKey(String key) {
        if (!StringUtils.hasText(key) || !KEY_PATTERN.matcher(key.trim()).matches()) {
            throw new IllegalArgumentException("NOVA_KEY_INVALID");
        }
        return key.trim();
    }

    private String normalizeDistributionKey(String key) {
        String normalized = normalizeChannelKey(key);
        if (DISTRIBUTION_SEEDS.stream().noneMatch(seed -> seed.key().equals(normalized))) {
            throw new IllegalArgumentException("NOVA_SOCIAL_DISTRIBUTION_KEY_INVALID");
        }
        return normalized;
    }

    private boolean isDistributionKey(String key) {
        if (!StringUtils.hasText(key) || !KEY_PATTERN.matcher(key.trim()).matches()) {
            return false;
        }
        String normalized = key.trim();
        return DISTRIBUTION_SEEDS.stream().anyMatch(seed -> seed.key().equals(normalized));
    }

    private String normalizePoolKey(String key) {
        String normalized = normalizeChannelKey(key);
        return POOL_SEEDS.stream()
                .map(PoolSeed::key)
                .filter(poolKey -> poolKey.equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(normalized);
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String slug(String name) {
        if (!StringUtils.hasText(name)) {
            return "novaChannel";
        }
        String[] parts = name.trim().replaceAll("[^A-Za-z0-9]+", " ").trim().split("\\s+");
        if (parts.length == 0 || !StringUtils.hasText(parts[0])) {
            return "novaChannel";
        }
        StringBuilder builder = new StringBuilder(parts[0].substring(0, 1).toLowerCase(Locale.ROOT));
        if (parts[0].length() > 1) {
            builder.append(parts[0].substring(1));
        }
        for (int index = 1; index < parts.length; index += 1) {
            if (StringUtils.hasText(parts[index])) {
                builder.append(parts[index].substring(0, 1).toUpperCase(Locale.ROOT));
                if (parts[index].length() > 1) {
                    builder.append(parts[index].substring(1));
                }
            }
        }
        return builder.toString();
    }

    private void audit(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("NOVA")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }

    private static ChannelSeed channel(String key, String name, String trigger, String tick, String cooldown, String phaseKeyed, String ctr, boolean enabled, int order) {
        return new ChannelSeed(key, name, trigger, tick, cooldown, phaseKeyed, new BigDecimal(ctr), enabled, order);
    }

    private static TemplateSeed template(String channel, String name, String cta, String version, String status) {
        return new TemplateSeed(channel, name, cta, version, status);
    }

    private static DistributionSeed distribution(String key, String name, int pct, String color) {
        return new DistributionSeed(key, name, pct, color);
    }

    private static PoolSeed pool(String key, String name, String description, int count) {
        return new PoolSeed(key, name, description, count);
    }

    private record ChannelSeed(String key, String name, String trigger, String tick, String cooldown, String phaseKeyed, BigDecimal ctr, boolean enabled, int order) {
    }

    private record TemplateSeed(String channel, String name, String cta, String version, String status) {
    }

    private record DistributionSeed(String key, String name, int pct, String color) {
    }

    private record PoolSeed(String key, String name, String description, int count) {
    }
}
