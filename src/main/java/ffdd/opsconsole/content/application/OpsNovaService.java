package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaOverview;
import ffdd.opsconsole.content.domain.NovaOptionView;
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
import java.time.Duration;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsNovaService {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[A-Za-z][A-Za-z0-9_]*}");
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)\\s*(s|min|h|d)$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TEMPLATE_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final List<NovaOptionView> TEMPLATE_CTA_OPTIONS = List.of(
            new NovaOptionView("NONE", "无跳转"),
            new NovaOptionView("/me/weekly", "每周回顾"),
            new NovaOptionView("/devices", "设备商城"),
            new NovaOptionView("/staking", "质押产品"),
            new NovaOptionView("/team", "团队"),
            new NovaOptionView("/earn", "收益任务"),
            new NovaOptionView("/support", "客服中心"));
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

    private final NovaRepository novaRepository;
    private final AuditLogService auditLogService;

    public ApiResult<NovaOverview> overview() {
        novaRepository.ensureTables();
        List<NovaChannelView> channels = novaRepository.channels();
        Map<String, Object> stats = novaRepository.stats();
        return ApiResult.ok(new NovaOverview(
                novaStats(stats),
                channels,
                List.of(),
                novaRepository.templates(),
                novaRepository.socialDistribution(),
                novaRepository.socialPools(),
                List.copyOf(TEMPLATE_STATUSES),
                TEMPLATE_CTA_OPTIONS,
                List.of(
                        "nx_nova_channel",
                        "nx_nova_template",
                        "nx_nova_social_distribution",
                        "nx_nova_social_pool",
                        "nx_notification")));
    }

    @Transactional
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
                false,
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

    @Transactional
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
        if (enabled && !current.get().enabled() && novaRepository.template(normalizedKey)
                .filter(template -> "PUBLISHED".equals(template.status()))
                .isEmpty()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_PUBLISHED_TEMPLATE_REQUIRED");
        }
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

    @Transactional
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
        if (request.enabled() && novaRepository.template(normalizedKey)
                .filter(template -> "PUBLISHED".equals(template.status()))
                .isEmpty()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_PUBLISHED_TEMPLATE_REQUIRED");
        }
        novaRepository.updateChannelStatus(normalizedKey, request.enabled(), request.operator(), request.reason().trim());
        NovaChannelView updated = novaRepository.channel(normalizedKey).orElseThrow();
        audit(request.enabled() ? "I2_NOVA_CHANNEL_RESTORED" : "I2_NOVA_CHANNEL_KILLED", normalizedKey,
                request.operator(), idempotencyKey, request.reason(), Map.of("enabled", request.enabled()));
        return ApiResult.ok(updated);
    }

    @Transactional
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
        if (novaRepository.template(normalizedKey).isPresent()) {
            novaRepository.deleteTemplate(normalizedKey, request.operator(), request.reason().trim());
        }
        novaRepository.deleteChannel(normalizedKey, request.operator(), request.reason().trim());
        audit("I2_NOVA_CHANNEL_DELETED", normalizedKey, request.operator(), idempotencyKey, request.reason(), Map.of("deleted", true));
        return ApiResult.ok();
    }

    @Transactional
    public ApiResult<NovaTemplateView> createTemplate(String idempotencyKey, NovaTemplateCreateRequest request) {
        ApiResult<NovaTemplateView> guard = requireTemplateCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        novaRepository.ensureTables();
        String channel = normalizeChannelKey(request.channel());
        if (novaRepository.channel(channel).isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_CHANNEL_NOT_FOUND");
        }
        if (novaRepository.template(channel).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_TEMPLATE_EXISTS");
        }
        novaRepository.createTemplate(
                channel,
                request.name().trim(),
                request.cta().trim(),
                request.version().trim(),
                request.titleZh().trim(),
                request.bodyZh().trim(),
                request.titleVi().trim(),
                request.bodyVi().trim(),
                trimToEmpty(request.titleEn()),
                trimToEmpty(request.bodyEn()),
                request.operator(),
                request.reason().trim());
        novaRepository.channel(channel)
                .filter(NovaChannelView::enabled)
                .ifPresent(channelView -> novaRepository.updateChannelStatus(
                        channel, false, request.operator(), request.reason().trim()));
        NovaTemplateView created = novaRepository.template(channel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_CREATED", channel, request.operator(), idempotencyKey, request.reason(), Map.of("status", "DRAFT"));
        return ApiResult.ok(created);
    }

    @Transactional
    public ApiResult<NovaTemplateView> updateTemplate(String channel, String idempotencyKey, NovaTemplateCreateRequest request) {
        String normalizedChannel = normalizeChannelKey(channel);
        ApiResult<NovaTemplateView> guard = requireTemplateCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (!normalizedChannel.equals(normalizeChannelKey(request.channel()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_CHANNEL_IMMUTABLE");
        }
        novaRepository.ensureTables();
        Optional<NovaTemplateView> current = novaRepository.template(normalizedChannel);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_TEMPLATE_NOT_FOUND");
        }
        novaRepository.updateTemplate(normalizedChannel, request.name().trim(), request.cta().trim(), request.version().trim(),
                request.titleZh().trim(), request.bodyZh().trim(), request.titleVi().trim(), request.bodyVi().trim(),
                trimToEmpty(request.titleEn()), trimToEmpty(request.bodyEn()), request.operator(), request.reason().trim());
        novaRepository.channel(normalizedChannel)
                .filter(NovaChannelView::enabled)
                .ifPresent(channelView -> novaRepository.updateChannelStatus(
                        normalizedChannel, false, request.operator(), request.reason().trim()));
        NovaTemplateView updated = novaRepository.template(normalizedChannel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_UPDATED", normalizedChannel, request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromVersion", current.get().version(), "toVersion", updated.version(), "status", updated.status()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<Void> deleteTemplate(String channel, String idempotencyKey, NovaDeleteRequest request) {
        String normalizedChannel = normalizeChannelKey(channel);
        ApiResult<Void> guard = requireVoidReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        novaRepository.ensureTables();
        Optional<NovaTemplateView> current = novaRepository.template(normalizedChannel);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_TEMPLATE_NOT_FOUND");
        }
        if ("PUBLISHED".equals(current.get().status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_PUBLISHED_TEMPLATE_CANNOT_DELETE");
        }
        novaRepository.deleteTemplate(normalizedChannel, request.operator(), request.reason().trim());
        audit("I2_NOVA_TEMPLATE_DELETED", normalizedChannel, request.operator(), idempotencyKey, request.reason(), Map.of("deleted", true));
        return ApiResult.ok();
    }

    @Transactional
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
        if ("PUBLISHED".equals(status) && !hasCompleteLocalizedContent(current.get())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_LOCALIZED_CONTENT_REQUIRED");
        }
        novaRepository.updateTemplateStatus(normalizedChannel, status, request.operator(), request.reason().trim());
        novaRepository.channel(normalizedChannel)
                .filter(NovaChannelView::enabled)
                .filter(channelView -> !"PUBLISHED".equals(status))
                .ifPresent(channelView -> novaRepository.updateChannelStatus(
                        normalizedChannel, false, request.operator(), request.reason().trim()));
        NovaTemplateView updated = novaRepository.template(normalizedChannel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_STATUS_CHANGED", normalizedChannel, request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.get().status(),
                "to", status));
        return ApiResult.ok(updated);
    }

    @Transactional
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

    @Transactional
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
        Duration tick = parseDuration(request.tick());
        Duration cooldown = parseDuration(request.cooldown());
        if (tick == null || cooldown == null || tick.isZero() || cooldown.isZero()
                || cooldown.compareTo(tick) < 0 || tick.compareTo(Duration.ofDays(365)) > 0
                || cooldown.compareTo(Duration.ofDays(365)) > 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_CHANNEL_CADENCE_INVALID");
        }
        return null;
    }

    private Duration parseDuration(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        var matcher = DURATION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
        try {
            return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> Duration.ofSeconds(amount);
                case "min" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> null;
            };
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private ApiResult<NovaTemplateView> requireTemplateCreate(String idempotencyKey, NovaTemplateCreateRequest request) {
        ApiResult<NovaTemplateView> reasonGuard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (reasonGuard != null) {
            return reasonGuard;
        }
        if (!StringUtils.hasText(request.channel()) || !StringUtils.hasText(request.name())
                || !StringUtils.hasText(request.cta()) || !StringUtils.hasText(request.version())
                || !StringUtils.hasText(request.titleZh()) || !StringUtils.hasText(request.bodyZh())
                || !StringUtils.hasText(request.titleVi()) || !StringUtils.hasText(request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_FIELDS_REQUIRED");
        }
        if (request.name().trim().length() > 128 || request.cta().trim().length() > 255
                || request.version().trim().length() > 32 || request.titleZh().trim().length() > 255
                || request.titleVi().trim().length() > 255 || trimToEmpty(request.titleEn()).length() > 255
                || request.bodyZh().trim().length() > 4000 || request.bodyVi().trim().length() > 4000
                || trimToEmpty(request.bodyEn()).length() > 4000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_FIELD_TOO_LONG");
        }
        if (TEMPLATE_CTA_OPTIONS.stream().noneMatch(option -> option.value().equals(request.cta().trim()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_CTA_UNSUPPORTED");
        }
        if ((StringUtils.hasText(request.titleEn()) != StringUtils.hasText(request.bodyEn()))
                || !placeholders(request.bodyZh()).equals(placeholders(request.bodyVi()))
                || (StringUtils.hasText(request.bodyEn()) && !placeholders(request.bodyZh()).equals(placeholders(request.bodyEn())))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_PLACEHOLDERS_MISMATCH");
        }
        return null;
    }

    private boolean hasCompleteLocalizedContent(NovaTemplateView template) {
        return StringUtils.hasText(template.titleZh()) && StringUtils.hasText(template.bodyZh())
                && StringUtils.hasText(template.titleVi()) && StringUtils.hasText(template.bodyVi())
                && placeholders(template.bodyZh()).equals(placeholders(template.bodyVi()))
                && (!StringUtils.hasText(template.bodyEn()) || placeholders(template.bodyZh()).equals(placeholders(template.bodyEn())));
    }

    private Set<String> placeholders(String value) {
        Set<String> values = new LinkedHashSet<>();
        if (!StringUtils.hasText(value)) {
            return values;
        }
        var matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
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
