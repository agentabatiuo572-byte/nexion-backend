package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.CopyAudiencePhaseProvider;
import ffdd.opsconsole.content.domain.NovaEventDrivenView;
import ffdd.opsconsole.content.domain.NovaOverview;
import ffdd.opsconsole.content.domain.NovaOptionView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialEventPage;
import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaSocialRenderedEventView;
import ffdd.opsconsole.content.domain.NovaSocialSyncResult;
import ffdd.opsconsole.content.domain.NovaStats;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.domain.TrustedNovaSocialEvent;
import ffdd.opsconsole.content.dto.NovaChannelStatusRequest;
import ffdd.opsconsole.content.dto.NovaChannelUpsertRequest;
import ffdd.opsconsole.content.dto.NovaDeleteRequest;
import ffdd.opsconsole.content.dto.NovaDistributionUpdateRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventStatusRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventSyncRequest;
import ffdd.opsconsole.content.dto.NovaTemplateCreateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final Set<String> SOCIAL_EVENT_STATUSES = Set.of("ACTIVE", "DISABLED", "EXPIRED");
    private static final List<NovaOptionView> SOCIAL_EVENT_TYPE_OPTIONS = List.of(
            new NovaOptionView("withdrawal", "提现到账"),
            new NovaOptionView("vrank", "V 等级晋升"),
            new NovaOptionView("genesis", "Genesis 成交"),
            new NovaOptionView("aiClient", "AI 客户消费（数据源未接入）"),
            new NovaOptionView("newUsers", "每小时新增用户"));
    private static final List<NovaOptionView> SOCIAL_EVENT_STATUS_OPTIONS = List.of(
            new NovaOptionView("ACTIVE", "有效"),
            new NovaOptionView("DISABLED", "已停用"),
            new NovaOptionView("EXPIRED", "已过期"));
    private static final List<NovaOptionView> TEMPLATE_CTA_OPTIONS = List.of(
            new NovaOptionView("NONE", "无跳转"),
            new NovaOptionView("/me/weekly", "每周回顾"),
            new NovaOptionView("/devices", "设备商城"),
            new NovaOptionView("/staking", "质押产品"),
            new NovaOptionView("/team", "团队"),
            new NovaOptionView("/earn", "收益任务"),
            new NovaOptionView("/support", "客服中心"));
    private static final List<NovaEventDrivenView> EVENT_DRIVEN_CHANNELS = List.of(
            new NovaEventDrivenView("risk-alert", "设备掉线或任务失败的异常状态机即时触发，不使用周期扫描。",
                    "E5/K 域异常状态机", "warn", "事件触发"),
            new NovaEventDrivenView("team_event", "v3 业务频道尚未接入 Nova cadence 配置。",
                    "F 域团队事件", "dim", "待整合"),
            new NovaEventDrivenView("staking_event", "v3 业务频道尚未接入 Nova cadence 配置。",
                    "G1 质押事件", "dim", "待整合"),
            new NovaEventDrivenView("market_event", "v3 业务频道尚未接入 Nova cadence 配置。",
                    "G3 市场事件", "dim", "待整合"),
            new NovaEventDrivenView("weekly-quest-refresh", "归入 quest 配置 key，具体触发时点由 H3 任务状态机负责。",
                    "H3 任务状态机", "warn", "事件触发"));
    private static final Map<String, DistributionOption> DISTRIBUTION_OPTIONS = List.of(
                    new DistributionOption("withdrawal", "提现到账", "var(--admin-cat-3)"),
                    new DistributionOption("vrank", "V 等级晋升", "var(--admin-cat-5)"),
                    new DistributionOption("genesis", "Genesis 成交", "var(--admin-cat-7)"),
                    new DistributionOption("aiClient", "AI 客户消费", "var(--admin-cat-2)"),
                    new DistributionOption("newUsers", "每小时新增用户", "var(--admin-cat-4)"))
            .stream()
            .collect(Collectors.toMap(DistributionOption::key, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    private final NovaRepository novaRepository;
    private final AuditLogService auditLogService;
    private final CopyAudiencePhaseProvider audiencePhaseProvider;

    public ApiResult<NovaOverview> overview() {
        novaRepository.ensureTables();
        String currentPhase = normalizePhase(audiencePhaseProvider.currentPhase());
        List<NovaChannelView> channels = novaRepository.channels().stream()
                .map(channel -> phaseAwareChannel(channel, currentPhase))
                .toList();
        Map<String, Object> stats = novaRepository.stats();
        return ApiResult.ok(new NovaOverview(
                novaStats(stats),
                channels,
                EVENT_DRIVEN_CHANNELS,
                novaRepository.templates(),
                novaRepository.socialDistribution(),
                publicEvents(novaRepository.socialEvents("", "", 20, 0)),
                SOCIAL_EVENT_TYPE_OPTIONS,
                SOCIAL_EVENT_STATUS_OPTIONS,
                List.copyOf(TEMPLATE_STATUSES),
                TEMPLATE_CTA_OPTIONS,
                List.of(
                        "nx_nova_channel",
                        "nx_nova_template",
                        "nx_nova_social_distribution",
                        "nx_nova_social_event",
                        "nx_notification")));
    }

    public ApiResult<List<NovaSocialEventView>> socialEvents(String eventType, String status) {
        ApiResult<NovaSocialEventPage> page = socialEventPage(eventType, status, 1, 100);
        if (page.getCode() != 0) {
            return ApiResult.fail(page.getCode(), page.getMessage());
        }
        return ApiResult.ok(page.getData().items());
    }

    public ApiResult<NovaSocialEventPage> socialEventPage(String eventType, String status, int page, int pageSize) {
        novaRepository.ensureTables();
        expireDueEvents();
        String normalizedType = trimToEmpty(eventType);
        String normalizedStatus = normalizeStatus(status);
        if (StringUtils.hasText(normalizedType) && !DISTRIBUTION_OPTIONS.containsKey(normalizedType)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_TYPE_UNSUPPORTED");
        }
        if (StringUtils.hasText(status) && !SOCIAL_EVENT_STATUSES.contains(normalizedStatus)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_STATUS_UNSUPPORTED");
        }
        if (page < 1 || page > 1_000_000 || pageSize < 1 || pageSize > 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_PAGE_INVALID");
        }
        String effectiveStatus = StringUtils.hasText(status) ? normalizedStatus : "";
        List<NovaSocialEventView> rows = novaRepository
                .socialEvents(normalizedType, effectiveStatus, pageSize, (page - 1) * pageSize).stream()
                .map(this::publicEvent)
                .toList();
        long total = novaRepository.countSocialEvents(normalizedType, effectiveStatus);
        return ApiResult.ok(new NovaSocialEventPage(rows, page, pageSize, total));
    }

    @Transactional
    public ApiResult<NovaSocialSyncResult> syncSocialEvents(String idempotencyKey, NovaSocialEventSyncRequest request) {
        ApiResult<NovaSocialSyncResult> guard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        int lookbackHours = request.lookbackHours() == null ? 24 : request.lookbackHours();
        int ttlHours = request.ttlHours() == null ? 12 : request.ttlHours();
        if (lookbackHours < 1 || lookbackHours > 168 || ttlHours < 1 || ttlHours > 168) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_SYNC_WINDOW_INVALID");
        }
        List<String> sourceTypes = request.sourceTypes() == null || request.sourceTypes().isEmpty()
                ? SOCIAL_EVENT_TYPE_OPTIONS.stream().map(NovaOptionView::value).toList()
                : request.sourceTypes().stream().map(this::normalizeEventType).distinct().toList();
        if (sourceTypes.stream().anyMatch(type -> !DISTRIBUTION_OPTIONS.containsKey(type))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_TYPE_UNSUPPORTED");
        }

        novaRepository.ensureTables();
        LocalDateTime now = LocalDateTime.now();
        List<NovaSocialSyncResult.SourceResult> results = new ArrayList<>();
        int discoveredTotal = 0;
        int insertedTotal = 0;
        int duplicateTotal = 0;
        for (String sourceType : sourceTypes) {
            String sourceTable = sourceTable(sourceType);
            if ("aiClient".equals(sourceType)) {
                results.add(new NovaSocialSyncResult.SourceResult(sourceType, "", "UNAVAILABLE", 0, 0, 0,
                        "尚无真实 AI 计费事件表，系统不会生成替代数据"));
                continue;
            }
            LocalDateTime until = "newUsers".equals(sourceType) ? now.truncatedTo(ChronoUnit.HOURS) : now;
            LocalDateTime since = until.minusHours(lookbackHours);
            List<TrustedNovaSocialEvent> discovered = novaRepository.trustedSourceEvents(sourceType, since, until);
            int inserted = 0;
            int duplicates = 0;
            int sourceOrdinal = 0;
            for (TrustedNovaSocialEvent source : discovered) {
                int currentOrdinal = sourceOrdinal++;
                if (!trustedSourceMatches(sourceType, sourceTable, source, since, until)) {
                    continue;
                }
                LocalDateTime expiresAt = source.occurredAt().plusHours(ttlHours);
                if (!expiresAt.isAfter(now)) {
                    continue;
                }
                if (novaRepository.socialEventSourceExists(source.eventType(), source.sourceSystem(), source.sourceEventId())) {
                    duplicates++;
                    continue;
                }
                ApiResult<NovaSocialEventView> ingested = ingestTrustedSocialEvent(
                        idempotencyKey + ":" + sourceType + ":" + currentOrdinal, source, expiresAt,
                        operator(request.operator()), request.reason());
                if (ingested.getCode() == 0) {
                    inserted++;
                }
            }
            discoveredTotal += discovered.size();
            insertedTotal += inserted;
            duplicateTotal += duplicates;
            results.add(new NovaSocialSyncResult.SourceResult(sourceType, sourceTable, "AVAILABLE",
                    discovered.size(), inserted, duplicates, "只同步已验证终态且仍在有效期内的真实事件"));
        }
        NovaSocialSyncResult result = new NovaSocialSyncResult(discoveredTotal, insertedTotal, duplicateTotal, List.copyOf(results));
        audit("I2_NOVA_SOCIAL_EVENTS_SYNCED", "social-events", operator(request.operator()), idempotencyKey, request.reason(), Map.of(
                "discovered", discoveredTotal, "inserted", insertedTotal, "duplicates", duplicateTotal,
                "sources", sourceTypes));
        return ApiResult.ok(result);
    }

    /** Trusted application adapters may call this; it is deliberately not exposed by the admin controller. */
    @Transactional
    public ApiResult<NovaSocialEventView> ingestTrustedSocialEvent(
            String idempotencyKey,
            TrustedNovaSocialEvent source,
            LocalDateTime expiresAt,
            String operator,
            String reason) {
        ApiResult<NovaSocialEventView> guard = requireReason(idempotencyKey, reason);
        if (guard != null) {
            return guard;
        }
        if (!validTrustedSource(source, expiresAt)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_SOURCE_INVALID");
        }
        if (!DISTRIBUTION_OPTIONS.containsKey(source.eventType())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_TYPE_UNSUPPORTED");
        }
        String expectedTable = sourceTable(source.eventType());
        if (!"NEXION_CORE".equals(source.sourceSystem()) || !expectedTable.equals(source.sourceTable())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_SOURCE_NOT_TRUSTED");
        }
        String authenticatedOperator = operator(operator);
        novaRepository.ensureTables();
        Optional<NovaSocialEventView> existing = novaRepository.socialEventBySource(
                source.eventType(), source.sourceSystem(), source.sourceEventId());
        if (existing.isPresent()) {
            return ApiResult.ok(publicEvent(existing.get()));
        }
        if (novaRepository.socialEventSourceExists(source.eventType(), source.sourceSystem(), source.sourceEventId())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_SOCIAL_EVENT_SOURCE_ALREADY_INGESTED");
        }
        boolean inserted = novaRepository.tryCreateSocialEvent(
                source, mask(source.actorName(), "匿名用户"), mask(source.city(), "未知地区"),
                "newUsers".equals(source.eventType()) ? peopleBand(source.amount()) : amountBand(source.amount(), source.amountUnit()),
                expiresAt, authenticatedOperator, reason.trim());
        Optional<NovaSocialEventView> created = novaRepository.socialEventBySource(
                source.eventType(), source.sourceSystem(), source.sourceEventId());
        if (!inserted || created.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_SOCIAL_EVENT_SOURCE_ALREADY_INGESTED");
        }
        audit("I2_NOVA_SOCIAL_EVENT_CREATED", publicReference(created.get()), authenticatedOperator, idempotencyKey, reason, Map.of(
                "eventType", source.eventType(), "sourceSystem", source.sourceSystem(), "sourceTable", source.sourceTable()));
        return ApiResult.ok(publicEvent(created.get()));
    }

    @Transactional
    public ApiResult<NovaSocialEventView> updateSocialEventStatus(
            long id, String idempotencyKey, NovaSocialEventStatusRequest request) {
        ApiResult<NovaSocialEventView> guard = requireReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String status = normalizeStatus(request.status());
        if (!Set.of("ACTIVE", "DISABLED", "EXPIRED").contains(status)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_STATUS_UNSUPPORTED");
        }
        novaRepository.ensureTables();
        Optional<NovaSocialEventView> current = novaRepository.socialEvent(id);
        if (current.isEmpty()) {
            return ApiResult.fail(404, "NOVA_SOCIAL_EVENT_NOT_FOUND");
        }
        if (status.equals(current.get().status())) {
            return ApiResult.ok(publicEvent(current.get()));
        }
        if ("EXPIRED".equals(current.get().status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_SOCIAL_EVENT_EXPIRED_IS_TERMINAL");
        }
        if ("ACTIVE".equals(status) && !current.get().expiresAt().isAfter(LocalDateTime.now())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_SOCIAL_EVENT_ALREADY_EXPIRED");
        }
        novaRepository.updateSocialEventStatus(id, status, operator(request.operator()), request.reason().trim());
        NovaSocialEventView updated = novaRepository.socialEvent(id).orElseThrow();
        audit("I2_NOVA_SOCIAL_EVENT_STATUS_CHANGED", String.valueOf(id), operator(request.operator()), idempotencyKey, request.reason(),
                Map.of("from", current.get().status(), "to", status));
        return ApiResult.ok(publicEvent(updated));
    }

    @Transactional
    public ApiResult<Void> deleteSocialEvent(long id, String idempotencyKey, NovaDeleteRequest request) {
        ApiResult<Void> guard = requireVoidReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        novaRepository.ensureTables();
        Optional<NovaSocialEventView> current = novaRepository.socialEvent(id);
        if (current.isEmpty()) {
            return ApiResult.ok();
        }
        novaRepository.deleteSocialEvent(id, operator(request.operator()), request.reason().trim());
        audit("I2_NOVA_SOCIAL_EVENT_DELETED", String.valueOf(id), operator(request.operator()), idempotencyKey, request.reason(),
                Map.of("eventType", current.get().eventType()));
        return ApiResult.ok();
    }

    @Transactional
    public ApiResult<Map<String, Integer>> expireSocialEvents(String idempotencyKey, NovaDeleteRequest request) {
        ApiResult<Void> guard = requireVoidReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        novaRepository.ensureTables();
        int expired = novaRepository.expireSocialEvents(LocalDateTime.now());
        audit("I2_NOVA_SOCIAL_EVENTS_EXPIRED", "social-events", operator(request.operator()), idempotencyKey, request.reason(),
                Map.of("expired", expired));
        return ApiResult.ok(Map.of("expired", expired));
    }

    public ApiResult<NovaSocialRenderedEventView> sampleSocialEvent(String language) {
        String normalizedLanguage = StringUtils.hasText(language) ? language.trim().toUpperCase(Locale.ROOT) : "ZH";
        if (!Set.of("ZH", "VI", "EN").contains(normalizedLanguage)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_SOCIAL_EVENT_LANGUAGE_UNSUPPORTED");
        }
        novaRepository.ensureTables();
        expireDueEvents();
        NovaChannelView channel = novaRepository.channel("social").orElse(null);
        if (channel == null || !channel.enabled()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_SOCIAL_CHANNEL_DISABLED");
        }
        NovaTemplateView template = novaRepository.template("social").orElse(null);
        if (template == null || !"PUBLISHED".equalsIgnoreCase(template.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_SOCIAL_TEMPLATE_NOT_PUBLISHED");
        }
        Map<String, Integer> weights = novaRepository.socialDistribution().stream()
                .collect(Collectors.toMap(NovaSocialDistributionItem::key, NovaSocialDistributionItem::pct,
                        (left, right) -> right, LinkedHashMap::new));
        LocalDateTime now = LocalDateTime.now();
        Map<String, List<NovaSocialEventView>> candidates = new LinkedHashMap<>();
        weights.forEach((type, weight) -> {
            if (weight > 0) {
                List<NovaSocialEventView> rows = novaRepository.activeSocialEventsByType(type, now, 100);
                if (!rows.isEmpty()) {
                    candidates.put(type, rows);
                }
            }
        });
        int totalWeight = candidates.keySet().stream().mapToInt(type -> weights.getOrDefault(type, 0)).sum();
        if (totalWeight <= 0) {
            return ApiResult.ok(null);
        }
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        String selectedType = null;
        for (String type : candidates.keySet()) {
            roll -= weights.getOrDefault(type, 0);
            if (roll < 0) {
                selectedType = type;
                break;
            }
        }
        List<NovaSocialEventView> selectedCandidates = candidates.get(selectedType);
        NovaSocialEventView selected = selectedCandidates.get(ThreadLocalRandom.current().nextInt(selectedCandidates.size()));
        NovaSocialEventView publicEvent = publicEvent(selected);
        NovaSocialMessageRenderer.RenderedMessage message =
                NovaSocialMessageRenderer.render(template, publicEvent, normalizedLanguage);
        return ApiResult.ok(new NovaSocialRenderedEventView(
                publicEvent.id(), publicEvent.eventType(), publicEvent.sourceEventId(), normalizedLanguage,
                message.title(), message.body(), publicEvent.expiresAt()));
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
                operator(request.operator()),
                request.reason().trim());
        NovaChannelView created = novaRepository.channel(key).orElseThrow();
        audit("I2_NOVA_CHANNEL_CREATED", key, operator(request.operator()), idempotencyKey, request.reason(), Map.of(
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
        if (isH1PhaseKeyedChannel(normalizedKey)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NOVA_PHASE_CADENCE_H1_READ_ONLY");
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
                operator(request.operator()),
                request.reason().trim());
        NovaChannelView updated = novaRepository.channel(normalizedKey).orElseThrow();
        audit("I2_NOVA_CHANNEL_UPDATED", normalizedKey, operator(request.operator()), idempotencyKey, request.reason(), Map.of(
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
        novaRepository.updateChannelStatus(normalizedKey, request.enabled(), operator(request.operator()), request.reason().trim());
        NovaChannelView updated = novaRepository.channel(normalizedKey).orElseThrow();
        audit(request.enabled() ? "I2_NOVA_CHANNEL_RESTORED" : "I2_NOVA_CHANNEL_KILLED", normalizedKey,
                operator(request.operator()), idempotencyKey, request.reason(), Map.of("enabled", request.enabled()));
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
            novaRepository.deleteTemplate(normalizedKey, operator(request.operator()), request.reason().trim());
        }
        novaRepository.deleteChannel(normalizedKey, operator(request.operator()), request.reason().trim());
        audit("I2_NOVA_CHANNEL_DELETED", normalizedKey, operator(request.operator()), idempotencyKey, request.reason(), Map.of("deleted", true));
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
                operator(request.operator()),
                request.reason().trim());
        novaRepository.channel(channel)
                .filter(NovaChannelView::enabled)
                .ifPresent(channelView -> novaRepository.updateChannelStatus(
                        channel, false, operator(request.operator()), request.reason().trim()));
        NovaTemplateView created = novaRepository.template(channel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_CREATED", channel, operator(request.operator()), idempotencyKey, request.reason(), Map.of("status", "DRAFT"));
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
                trimToEmpty(request.titleEn()), trimToEmpty(request.bodyEn()), operator(request.operator()), request.reason().trim());
        novaRepository.channel(normalizedChannel)
                .filter(NovaChannelView::enabled)
                .ifPresent(channelView -> novaRepository.updateChannelStatus(
                        normalizedChannel, false, operator(request.operator()), request.reason().trim()));
        NovaTemplateView updated = novaRepository.template(normalizedChannel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_UPDATED", normalizedChannel, operator(request.operator()), idempotencyKey, request.reason(), Map.of(
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
        novaRepository.deleteTemplate(normalizedChannel, operator(request.operator()), request.reason().trim());
        audit("I2_NOVA_TEMPLATE_DELETED", normalizedChannel, operator(request.operator()), idempotencyKey, request.reason(), Map.of("deleted", true));
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
        novaRepository.updateTemplateStatus(normalizedChannel, status, operator(request.operator()), request.reason().trim());
        novaRepository.channel(normalizedChannel)
                .filter(NovaChannelView::enabled)
                .filter(channelView -> !"PUBLISHED".equals(status))
                .ifPresent(channelView -> novaRepository.updateChannelStatus(
                        normalizedChannel, false, operator(request.operator()), request.reason().trim()));
        NovaTemplateView updated = novaRepository.template(normalizedChannel).orElseThrow();
        audit("I2_NOVA_TEMPLATE_STATUS_CHANGED", normalizedChannel, operator(request.operator()), idempotencyKey, request.reason(), Map.of(
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
            novaRepository.upsertDistribution(key, option.name(), value, option.color(), operator(request.operator()), request.reason().trim());
        });
        List<NovaSocialDistributionItem> updated = novaRepository.socialDistribution();
        audit("I2_NOVA_SOCIAL_DISTRIBUTION_CHANGED", "social", operator(request.operator()), idempotencyKey, request.reason(), Map.of("total", total));
        return ApiResult.ok(updated);
    }

    private void expireDueEvents() {
        int expired = novaRepository.expireSocialEvents(LocalDateTime.now());
        if (expired > 0) {
            audit("I2_NOVA_SOCIAL_EVENTS_AUTO_EXPIRED", "social-events", "system", "system-auto-expire",
                    "事件超过有效期自动过期", Map.of("expired", expired));
        }
    }

    private boolean trustedSourceMatches(String eventType, String table, TrustedNovaSocialEvent source,
                                         LocalDateTime since, LocalDateTime until) {
        return source != null
                && eventType.equals(source.eventType())
                && "NEXION_CORE".equals(source.sourceSystem())
                && table.equals(source.sourceTable())
                && source.occurredAt() != null
                && !source.occurredAt().isBefore(since)
                && source.occurredAt().isBefore(until.plusNanos(1));
    }

    private boolean validTrustedSource(TrustedNovaSocialEvent source, LocalDateTime expiresAt) {
        return source != null
                && StringUtils.hasText(source.eventType())
                && StringUtils.hasText(source.sourceEventId())
                && source.sourceEventId().length() <= 160
                && StringUtils.hasText(source.sourceSystem())
                && StringUtils.hasText(source.sourceTable())
                && source.occurredAt() != null
                && expiresAt != null
                && expiresAt.isAfter(source.occurredAt())
                && expiresAt.isAfter(LocalDateTime.now())
                && trimToEmpty(source.actorName()).length() <= 255
                && trimToEmpty(source.city()).length() <= 255
                && trimToEmpty(source.amountUnit()).length() <= 16
                && trimToEmpty(source.sourceNote()).length() <= 512
                && (source.amount() == null || source.amount().compareTo(BigDecimal.ZERO) >= 0);
    }

    private String normalizeEventType(String value) {
        return trimToEmpty(value);
    }

    private String sourceTable(String eventType) {
        return switch (eventType) {
            case "withdrawal" -> "nx_withdrawal_order";
            case "vrank" -> "nx_user_level_log";
            case "genesis" -> "nx_genesis_order";
            case "newUsers" -> "nx_user";
            case "aiClient" -> "";
            default -> "";
        };
    }

    private String mask(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String normalized = value.trim();
        int firstCodePoint = normalized.codePointAt(0);
        return new String(Character.toChars(firstCodePoint)) + "***";
    }

    private String amountBand(BigDecimal amount, String unit) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }
        String band;
        if (amount.compareTo(new BigDecimal("1000")) < 0) {
            band = "<1K";
        } else if (amount.compareTo(new BigDecimal("5000")) < 0) {
            band = "1K–5K";
        } else if (amount.compareTo(new BigDecimal("10000")) < 0) {
            band = "5K–10K";
        } else if (amount.compareTo(new BigDecimal("50000")) < 0) {
            band = "10K–50K";
        } else {
            band = "50K+";
        }
        return band + (StringUtils.hasText(unit) ? " " + unit.trim().toUpperCase(Locale.ROOT) : "");
    }

    private String peopleBand(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.TEN) < 0) {
            return "";
        }
        int count = amount.intValue();
        if (count < 50) return "10–49 人";
        if (count < 100) return "50–99 人";
        if (count < 500) return "100–499 人";
        if (count < 1000) return "500–999 人";
        return "1000+ 人";
    }

    private List<NovaSocialEventView> publicEvents(List<NovaSocialEventView> events) {
        return events == null ? List.of() : events.stream().map(this::publicEvent).toList();
    }

    private NovaSocialEventView publicEvent(NovaSocialEventView event) {
        return new NovaSocialEventView(
                event.id(), event.eventType(), publicReference(event), event.actorDisplay(), event.cityDisplay(),
                event.amountDisplay(), event.sourceNote(), event.sourceSystem(), event.sourceTable(), event.status(),
                event.occurredAt(), event.expiresAt(), event.verifiedAt(), event.lastDispatchedAt(), event.dispatchCount(),
                event.createdAt(), event.updatedAt());
    }

    private String publicReference(NovaSocialEventView event) {
        return "evt_" + Long.toUnsignedString(event.id(), 36);
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
        Set<String> usedPlaceholders = new LinkedHashSet<>();
        List.of(request.titleZh(), request.bodyZh(), request.titleVi(), request.bodyVi(),
                        trimToEmpty(request.titleEn()), trimToEmpty(request.bodyEn()))
                .forEach(value -> usedPlaceholders.addAll(placeholders(value)));
        if (!NovaSocialMessageRenderer.SUPPORTED_PLACEHOLDERS.containsAll(usedPlaceholders)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NOVA_TEMPLATE_PLACEHOLDER_UNSUPPORTED");
        }
        return null;
    }

    private boolean hasCompleteLocalizedContent(NovaTemplateView template) {
        return StringUtils.hasText(template.titleZh()) && StringUtils.hasText(template.bodyZh())
                && StringUtils.hasText(template.titleVi()) && StringUtils.hasText(template.bodyVi())
                && placeholders(template.bodyZh()).equals(placeholders(template.bodyVi()))
                && (!StringUtils.hasText(template.bodyEn()) || placeholders(template.bodyZh()).equals(placeholders(template.bodyEn())))
                && NovaSocialMessageRenderer.SUPPORTED_PLACEHOLDERS.containsAll(placeholders(template.titleZh()))
                && NovaSocialMessageRenderer.SUPPORTED_PLACEHOLDERS.containsAll(placeholders(template.bodyZh()))
                && NovaSocialMessageRenderer.SUPPORTED_PLACEHOLDERS.containsAll(placeholders(template.titleVi()))
                && NovaSocialMessageRenderer.SUPPORTED_PLACEHOLDERS.containsAll(placeholders(template.bodyVi()))
                && NovaSocialMessageRenderer.SUPPORTED_PLACEHOLDERS.containsAll(placeholders(template.titleEn()))
                && NovaSocialMessageRenderer.SUPPORTED_PLACEHOLDERS.containsAll(placeholders(template.bodyEn()));
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
        if (!validReason(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<Void> requireVoidReason(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!validReason(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private NovaChannelView phaseAwareChannel(NovaChannelView channel, String currentPhase) {
        if (channel == null || !isH1PhaseKeyedChannel(channel.key())) {
            return channel;
        }
        String effectiveCooldown;
        if (!currentPhase.matches("P[1-6]")) {
            effectiveCooldown = channel.cooldown();
            currentPhase = "不可用";
        } else if ("tradein".equals(channel.key())) {
            effectiveCooldown = switch (currentPhase) {
                case "P1", "P2" -> "skip";
                case "P3", "P4" -> "60 min";
                default -> "24h";
            };
        } else {
            effectiveCooldown = switch (currentPhase) {
                case "P1", "P2" -> "30d";
                case "P3", "P4" -> "7d";
                default -> "84h";
            };
        }
        String phaseLabel = channel.phaseKeyed() + " · H1 当前 " + currentPhase + " → " + effectiveCooldown;
        return new NovaChannelView(
                channel.key(), channel.name(), channel.trigger(), channel.tick(), effectiveCooldown,
                phaseLabel, channel.ctr(), channel.enabled());
    }

    private boolean isH1PhaseKeyedChannel(String key) {
        return "tradein".equals(key) || "taskLockMonthly".equals(key);
    }

    private String normalizePhase(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private boolean validReason(String reason) {
        return StringUtils.hasText(reason)
                && reason.trim().length() >= 8
                && reason.trim().length() <= 200;
    }

    private String operator(String requestedOperator) {
        String resolved = AdminActorResolver.resolve(requestedOperator);
        return StringUtils.hasText(resolved) ? resolved : "system";
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
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("NOVA")
                .resourceId(resourceId)
                .actorUsername(operator(operator))
                .detail(fullDetail)
                .build());
    }

    private record DistributionOption(String key, String name, String color) {
    }

}
