package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsSandboxMapper;
import ffdd.opsconsole.bi.web.BehaviorEventRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BehaviorAnalyticsService {
    private static final Pattern ROUTE = Pattern.compile("^/pages/[a-z0-9-]+/[a-z0-9-]+$");
    private static final Pattern SESSION = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern CLIENT_EVENT_ID = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern ELEMENT = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(?:-[A-Z]{2})?$");
    private static final Set<String> EVENTS = Set.of("app.page_viewed", "app.element_clicked");
    private static final Set<String> DEVICES = Set.of("APP", "H5", "MP");
    private static final Set<String> ZONES = Set.of("TOP", "MAIN_CTA", "CONTENT", "BOTTOM");
    private static final long MAX_DWELL_MS = 86_400_000L;
    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private static final String BUSINESS_TIME_ZONE = "UTC+08:00";

    private final BehaviorAnalyticsMapper mapper;
    private final BehaviorAnalyticsSandboxMapper sandboxMapper;
    private final EventOutboxService outbox;
    private final AuditLogService auditLogService;
    private final Environment environment;
    @Value("${nexion.analytics.pseudonym-secret:${jwt.secret:nexion-l6-local-only}}")
    private final String pseudonymSecret;
    @Value("${nexion.analytics.acceptance-run-id:}")
    private final String acceptanceRunId;

    @Transactional
    public ApiResult<Map<String, Object>> ingest(Long userId, BehaviorEventRequest request) {
        return ingestValidated(userId, request);
    }

    @Transactional
    public ApiResult<Map<String,Object>> ingestFixture(Long userId,BehaviorEventRequest request){
        // Test/acceptance fixtures obey the same isolated path as acceptance
        // traffic; they are never allowed to seed production BI/outbox rows.
        return ingestValidated(userId, request);
    }

    private ApiResult<Map<String,Object>> ingestValidated(Long userId, BehaviorEventRequest request) {
        require(userId != null && userId > 0, "USER_AUTH_REQUIRED");
        String runtimeEnvironment = BehaviorAnalyticsAcceptanceProfileCondition
                .sourceEnvironmentFor(environment.getActiveProfiles());
        require(runtimeEnvironment != null, "L6_ANALYTICS_PROFILE_FORBIDDEN");
        // Environment provenance is derived only from one active server
        // profile. A request has no environment field and a property cannot
        // override this decision.
        String sourceEnvironment = runtimeEnvironment;
        Boolean sandboxAccount = mapper.isSandboxUser(userId);
        require(sandboxAccount != null && sandboxAccount == "SANDBOX".equals(runtimeEnvironment),
                "L6_ACCOUNT_ENVIRONMENT_MISMATCH");
        require(request != null && EVENTS.contains(request.eventName()), "L6_EVENT_NOT_ALLOWED");
        require(CLIENT_EVENT_ID.matcher(text(request.clientEventId())).matches(), "L6_CLIENT_EVENT_ID_INVALID");
        String route = normalizeRoute(request.route());
        BehaviorAnalyticsMapper.CatalogRow page = mapper.findTrackedPage(route);
        require(page != null, "L6_ROUTE_NOT_TRACKED");
        require(SESSION.matcher(text(request.sessionId())).matches(), "L6_SESSION_INVALID");
        String device = normalizeDevice(request.deviceType());
        String locale = normalizeLocale(request.locale());
        String sessionHash = pseudonym("session", request.sessionId());
        String actorHash = pseudonym("actor", String.valueOf(userId));
        LocalDateTime occurredAt = normalizeClientTime(request.clientTs());

        Long dwellMs = null;
        Double xNorm = null;
        Double yNorm = null;
        String zone = null;
        String elementId = null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("anon_id", actorHash);
        payload.put("session_id", sessionHash);
        payload.put("route", route);
        payload.put("page_level", page.pageLevel());
        payload.put("parent_l1", page.parentL1());
        payload.put("parent_l2", page.parentL2());
        payload.put("platform", device.toLowerCase(Locale.ROOT));
        payload.put("locale", locale);
        payload.put("source_environment",sourceEnvironment);

        if ("app.page_viewed".equals(request.eventName())) {
            require(request.dwellMs() != null && request.dwellMs() >= 0 && request.dwellMs() <= MAX_DWELL_MS,
                    "L6_DWELL_INVALID");
            dwellMs = request.dwellMs();
            payload.put("dwell_ms", dwellMs);
        } else {
            require(finiteUnit(request.xNorm()) && finiteUnit(request.yNorm()), "L6_COORDINATE_INVALID");
            xNorm = round4(request.xNorm());
            yNorm = round4(request.yNorm());
            String requestedZone = text(request.zone()).toUpperCase(Locale.ROOT);
            String derivedZone = yNorm < 0.25d ? "TOP" : yNorm > 0.75d ? "BOTTOM" : "CONTENT";
            require(ZONES.contains(requestedZone), "L6_ZONE_INVALID");
            require(requestedZone.equals(derivedZone)
                    || ("MAIN_CTA".equals(requestedZone) && "CONTENT".equals(derivedZone)), "L6_ZONE_INVALID");
            zone = requestedZone;
            if (StringUtils.hasText(request.elementId())) {
                elementId = text(request.elementId()).toLowerCase(Locale.ROOT);
                require(ELEMENT.matcher(elementId).matches(), "L6_ELEMENT_ID_INVALID");
            }
            payload.put("x_norm", xNorm);
            payload.put("y_norm", yNorm);
            payload.put("zone", zone);
            if (elementId != null) payload.put("element_id", elementId);
        }
        final Long validatedDwellMs = dwellMs;
        final Double validatedXNorm = xNorm;
        final Double validatedYNorm = yNorm;
        final String validatedZone = zone;
        final String validatedElementId = elementId;
        String fingerprint = fingerprint(runtimeEnvironment, actorHash, sessionHash, request.clientTs(), request.eventName(),
                route, device, locale, validatedDwellMs, validatedXNorm, validatedYNorm, validatedZone, validatedElementId);

        // Acceptance traffic has completed the same contract validation as a
        // production event, but it must not contaminate the production L6
        // event outbox or fact table. The status is explicit to the caller;
        // this is a server-derived environment decision, never request data.
        if ("SANDBOX".equals(sourceEnvironment)) {
            requireValidAcceptanceRunId();
            return withSandboxSessionAuthority(sessionHash, () -> ingestSandbox(request, sessionHash, actorHash, route, page,
                    validatedDwellMs, validatedXNorm, validatedYNorm, validatedZone, validatedElementId,
                    device, locale, occurredAt, fingerprint));
        }
        return withProductionSessionAuthority(sessionHash, () -> ingestProduction(request, sessionHash, actorHash, route,
                page, validatedDwellMs, validatedXNorm, validatedYNorm, validatedZone, validatedElementId,
                device, locale, occurredAt, fingerprint, sourceEnvironment, payload));
    }

    /** All decisions that depend on mutable session state execute after its authority lock. */
    private ApiResult<Map<String, Object>> ingestProduction(BehaviorEventRequest request, String sessionHash,
            String actorHash, String route, BehaviorAnalyticsMapper.CatalogRow page, Long dwellMs, Double xNorm,
            Double yNorm, String zone, String elementId, String device, String locale, LocalDateTime occurredAt,
            String fingerprint, String sourceEnvironment, Map<String, Object> payload) {
        BehaviorAnalyticsMapper.ExistingEventRow existing = mapper.findByClientEventId(request.clientEventId());
        if (existing != null) {
            requireClientEventFingerprint(fingerprint, existing.fingerprint());
            return ApiResult.ok(linked("accepted", false, "duplicate", true, "backfilled", false));
        }
        String dedupeKey = pseudonym("event", request.clientEventId());
        BehaviorAnalyticsMapper.ExistingEventRow dedupeWinner = mapper.findByDedupeKey(dedupeKey);
        if (dedupeWinner != null) {
            requireClientEventFingerprint(fingerprint, dedupeWinner.fingerprint());
            return ApiResult.ok(linked("accepted", false, "duplicate", true, "backfilled", false));
        }
        LocalDateTime latestSession = mapper.latestSessionEventAt(sessionHash);
        require(latestSession == null || !occurredAt.isBefore(latestSession), "L6_EVENT_OUT_OF_ORDER");
        long perMinuteLimit = "app.element_clicked".equals(request.eventName()) ? 180L : 120L;
        if (mapper.countRecent(sessionHash, request.eventName(), LocalDateTime.now(BUSINESS_ZONE).minusMinutes(1)) >= perMinuteLimit) {
            throw new BizException(429, "L6_EVENT_RATE_LIMITED");
        }
        if ("app.element_clicked".equals(request.eventName())) {
            LocalDateTime latest = mapper.latestEventAt(sessionHash, request.eventName());
            if (latest != null && occurredAt.isBefore(latest.plusNanos(300_000_000L))) {
                throw new BizException(429, "L6_CLICK_THROTTLED");
            }
        }
        // Claim the fact first. This is the transaction's atomic winner
        // election: a concurrent replay that loses its unique key must never
        // publish an outbox row before it is acknowledged as duplicate.
        String claimEventId = UUID.randomUUID().toString().replace("-", "");
        try {
            int claimed = mapper.insertFact(new BehaviorAnalyticsMapper.BehaviorFactRow(
                    claimEventId, request.clientEventId(), dedupeKey, fingerprint, request.eventName(), sessionHash, actorHash, route, page.pageLevel(), page.parentL1(), page.parentL2(),
                    dwellMs, xNorm, yNorm, zone, elementId, device, locale, sourceEnvironment, occurredAt));
            if (claimed != 1) throw new BizException(409, "L6_FACT_CLAIM_LOST");
        } catch (DuplicateKeyException ignored) {
            // A replay can pass the optimistic pre-read concurrently. The
            // unique index selects the persisted winner; re-read it by both
            // authority keys and only acknowledge an exact canonical match.
            BehaviorAnalyticsMapper.ExistingEventRow winner = mapper.findByClientEventId(request.clientEventId());
            if (winner == null) winner = mapper.findByDedupeKey(dedupeKey);
            if (winner == null) throw new BizException(409, "L6_DUPLICATE_WRITE_CONFLICT");
            requireClientEventFingerprint(fingerprint, winner.fingerprint());
            return ApiResult.ok(linked("accepted", false, "duplicate", true, "backfilled", false));
        }
        EventOutboxService.ClientAnalyticsPublishResult published =
                outbox.publishTrustedClientAnalyticsEvent(sessionHash, actorHash, request.eventName(), payload);
        if (!published.sampledIn()) {
            if (mapper.deleteClaim(claimEventId, request.clientEventId()) != 1) {
                throw new BizException(409, "L6_FACT_CLAIM_LOST");
            }
            return ApiResult.ok(linked("accepted", true, "duplicate", false, "sampledIn", false));
        }
        String eventId = published.eventId();
        if (mapper.replaceClaimEventId(eventId, claimEventId, request.clientEventId()) != 1) {
            // This exception rolls the transaction back, including the just
            // created outbox record; returning success here could orphan it.
            throw new BizException(409, "L6_FACT_CLAIM_LOST");
        }
        return ApiResult.ok(linked("accepted", true, "duplicate", false, "sampledIn", true, "eventId", eventId));
    }

    /**
     * Acceptance events are durable only inside their own fact table. They
     * deliberately bypass EventOutboxService and the production fact mapper;
     * the server owns both sampling and environment provenance.
     */
    private ApiResult<Map<String, Object>> ingestSandbox(BehaviorEventRequest request, String sessionHash,
            String actorHash, String route, BehaviorAnalyticsMapper.CatalogRow page, Long dwellMs, Double xNorm,
            Double yNorm, String zone, String elementId, String device, String locale, LocalDateTime occurredAt,
            String fingerprint) {
        requireValidAcceptanceRunId();
        String observationToken = observationToken(acceptanceRunId, actorHash, sessionHash);
        BehaviorAnalyticsSandboxMapper.ExistingEventRow existing = sandboxMapper.findByClientEventId(acceptanceRunId, request.clientEventId());
        if (existing != null) {
            requireClientEventFingerprint(fingerprint, existing.fingerprint());
            return ApiResult.ok(linked("accepted", false, "duplicate", true, "backfilled", false,
                    "source", "mock", "sourceEnvironment", "SANDBOX", "runId", acceptanceRunId,
                    "observationToken", observationToken));
        }
        String dedupeKey = pseudonym("sandbox-event", request.clientEventId());
        BehaviorAnalyticsSandboxMapper.ExistingEventRow dedupeWinner = sandboxMapper.findByDedupeKey(acceptanceRunId, dedupeKey);
        if (dedupeWinner != null) {
            requireClientEventFingerprint(fingerprint, dedupeWinner.fingerprint());
            return ApiResult.ok(linked("accepted", false, "duplicate", true, "backfilled", false,
                    "source", "mock", "sourceEnvironment", "SANDBOX", "runId", acceptanceRunId,
                    "observationToken", observationToken));
        }
        LocalDateTime latestSession = sandboxMapper.latestSessionEventAt(acceptanceRunId, sessionHash);
        require(latestSession == null || !occurredAt.isBefore(latestSession), "L6_EVENT_OUT_OF_ORDER");
        long perMinuteLimit = "app.element_clicked".equals(request.eventName()) ? 180L : 120L;
        if (sandboxMapper.countRecent(acceptanceRunId, sessionHash, request.eventName(), LocalDateTime.now(BUSINESS_ZONE).minusMinutes(1)) >= perMinuteLimit) {
            throw new BizException(429, "L6_EVENT_RATE_LIMITED");
        }
        if ("app.element_clicked".equals(request.eventName())) {
            LocalDateTime latest = sandboxMapper.latestEventAt(acceptanceRunId, sessionHash, request.eventName());
            if (latest != null && occurredAt.isBefore(latest.plusNanos(300_000_000L))) {
                throw new BizException(429, "L6_CLICK_THROTTLED");
            }
        }
        String eventId = UUID.randomUUID().toString();
        try {
            sandboxMapper.insertFact(new BehaviorAnalyticsSandboxMapper.SandboxFactRow(eventId, request.clientEventId(),
                    dedupeKey, fingerprint, acceptanceRunId, observationToken, request.eventName(), sessionHash, actorHash, route, page.pageLevel(), page.parentL1(),
                    page.parentL2(), dwellMs, xNorm, yNorm, zone, elementId, device, locale, occurredAt));
        } catch (DuplicateKeyException ignored) {
            // Sandbox uniqueness is run-scoped. Never inspect another run's
            // row when resolving a concurrent client replay.
            BehaviorAnalyticsSandboxMapper.ExistingEventRow winner = sandboxMapper.findByClientEventId(acceptanceRunId, request.clientEventId());
            if (winner == null) winner = sandboxMapper.findByDedupeKey(acceptanceRunId, dedupeKey);
            if (winner == null) throw new BizException(409, "L6_DUPLICATE_WRITE_CONFLICT");
            requireClientEventFingerprint(fingerprint, winner.fingerprint());
            return ApiResult.ok(linked("accepted", false, "duplicate", true, "backfilled", false,
                    "source", "mock", "sourceEnvironment", "SANDBOX", "runId", acceptanceRunId,
                    "observationToken", observationToken));
        }
        return ApiResult.ok(linked("accepted", true, "duplicate", false, "eventId", eventId,
                "source", "mock", "sourceEnvironment", "SANDBOX", "runId", acceptanceRunId,
                "observationToken", observationToken));
    }

    public ApiResult<Map<String, Object>> behavior(String window, String device, String locale, String depth, String sort) {
        requireProductionL6Surface();
        Query query = query(window, device, locale);
        String normalizedDepth = normalizeDepth(depth);
        List<BehaviorAnalyticsMapper.ActivityRow> rows = new ArrayList<>(
                mapper.activity(query.startAt(), query.endAt(), query.device(), query.locale(), normalizedDepth));
        sortActivity(rows, sort);
        return ApiResult.ok(linked(
                "available", true,
                "status", "AVAILABLE",
                "window", query.window(),
                "device", query.device() == null ? "ALL" : query.device(),
                "locale", query.locale() == null ? "ALL" : query.locale(),
                "depth", normalizedDepth,
                "businessTimeZone", BUSINESS_TIME_ZONE,
                "lateArrivalPolicy", "rejected_retry_in_order",
                "activity", rows,
                "dailyTrend", mapper.dailyTrend(query.startAt(), query.endAt(), query.device(), query.locale()),
                "weeklyTrend", mapper.weeklyTrend(query.startAt(), query.endAt(), query.device(), query.locale()),
                "privacy", linked("aggregatedOnly", true, "rawTextStored", false, "directUserIdStored", false),
                "quality", linked("clientEventIdDeduplicated", true, "outOfOrderRejected", true,
                        "ctrDenominator", "page_viewed_pv"),
                "sources", List.of("nx_behavior_event_fact", "nx_behavior_page_catalog", "nx_event_outbox")));
    }

    /** A separate, read-only acceptance observation surface; production L6 never calls this fact table. */
    public ApiResult<Map<String, Object>> acceptanceBehavior(String runId, String actorHash, String sessionHash,
            String route, LocalDateTime from, LocalDateTime to) {
        return acceptanceBehavior(runId, null, actorHash, sessionHash, route, from, to);
    }

    public ApiResult<Map<String, Object>> acceptanceBehavior(String runId, String observationToken, String actorHash,
            String sessionHash, String route, LocalDateTime from, LocalDateTime to) {
        require("SANDBOX".equals(BehaviorAnalyticsAcceptanceProfileCondition
                .sourceEnvironmentFor(environment.getActiveProfiles())), "L6_ACCEPTANCE_PROFILE_FORBIDDEN");
        require(acceptanceRunId != null && acceptanceRunId.equals(text(runId)), "L6_ACCEPTANCE_RUN_ID_INVALID");
        if (StringUtils.hasText(observationToken)) {
            require(observationToken.matches("^[a-f0-9]{64}$"), "L6_ACCEPTANCE_CREDENTIAL_INVALID");
            BehaviorAnalyticsSandboxMapper.ObservationScope scope = sandboxMapper.findObservationScope(observationToken);
            require(scope != null && acceptanceRunId.equals(scope.runId()), "L6_ACCEPTANCE_CREDENTIAL_INVALID");
            require((actorHash == null || actorHash.equals(scope.actorHash()))
                    && (sessionHash == null || sessionHash.equals(scope.sessionHash())), "L6_ACCEPTANCE_SCOPE_INVALID");
            actorHash = scope.actorHash();
            sessionHash = scope.sessionHash();
        }
        require(actorHash != null && sessionHash != null, "L6_ACCEPTANCE_SCOPE_REQUIRED");
        require(actorHash.matches("^[a-f0-9]{64}$") && sessionHash.matches("^[a-f0-9]{64}$"),
                "L6_ACCEPTANCE_HASH_INVALID");
        String normalizedRoute = text(route).isEmpty() ? null : normalizeRoute(route);
        require(text(route).isEmpty() || !normalizedRoute.isEmpty(), "L6_ROUTE_NOT_TRACKED");
        require(normalizedRoute == null || mapper.findTrackedPage(normalizedRoute) != null, "L6_ROUTE_NOT_TRACKED");
        require(from != null && to != null && !from.isAfter(to) && !to.isAfter(from.plusDays(31)),
                "L6_ACCEPTANCE_TIME_WINDOW_INVALID");
        BehaviorAnalyticsSandboxMapper.SandboxSummary summary = sandboxMapper.summary(acceptanceRunId,
                actorHash, sessionHash, normalizedRoute, from, to);
        long pageViews = summary == null || summary.pageViews() == null ? 0L : summary.pageViews();
        long clicks = summary == null || summary.clicks() == null ? 0L : summary.clicks();
        long matchedFacts = pageViews + clicks;
        require(matchedFacts > 0, "L6_ACCEPTANCE_FACTS_NOT_FOUND");
        BehaviorAnalyticsSandboxMapper.IngestWindow ingestWindow = sandboxMapper.ingestWindow(acceptanceRunId,
                actorHash, sessionHash, normalizedRoute, from, to);
        require(ingestWindow != null && ingestWindow.firstIngestedAt() != null && ingestWindow.lastIngestedAt() != null,
                "L6_ACCEPTANCE_INGEST_WINDOW_UNAVAILABLE");
        // Both production tables record their durable write time. This catches
        // a wrongly routed event even if its client occurred_at was backdated.
        LocalDateTime proofFrom = ingestWindow.firstIngestedAt().minusMinutes(1);
        LocalDateTime proofTo = ingestWindow.lastIngestedAt().plusMinutes(1);
        long productionFactDelta = sandboxMapper.productionFactDelta(actorHash, sessionHash, proofFrom, proofTo);
        long productionOutboxDelta = sandboxMapper.productionOutboxDelta(sessionHash, proofFrom, proofTo);
        require(productionFactDelta == 0 && productionOutboxDelta == 0, "L6_ACCEPTANCE_PRODUCTION_CONTAMINATION");
        return ApiResult.ok(linked("available", true, "source", "mock", "sourceEnvironment", "SANDBOX",
                "status", "AVAILABLE", "runId", acceptanceRunId, "actorHash", actorHash,
                "sessionHash", sessionHash, "windowFrom", from, "windowTo", to,
                "pageViews", pageViews, "clicks", clicks, "matchedFacts", matchedFacts, "productionDelta",
                linked("factRows", productionFactDelta,
                        "outboxRows", productionOutboxDelta,
                        "occurredWindowFrom", from, "occurredWindowTo", to,
                        "ingestProofFrom", proofFrom, "ingestProofTo", proofTo, "expected", "0/0")));
    }

    public ApiResult<Map<String, Object>> clickHeat(String route, String window, String device, String locale, String depth) {
        requireProductionL6Surface();
        String normalizedDepth = normalizeDepth(depth);
        require(!"L1".equals(normalizedDepth) && !"L2".equals(normalizedDepth), "L6_AGGREGATE_NODE_NO_COORDINATES");
        String normalizedRoute = normalizeRoute(route);
        BehaviorAnalyticsMapper.CatalogRow page = mapper.findTrackedPage(normalizedRoute);
        require(page != null, "L6_ROUTE_NOT_TRACKED");
        Query query = query(window, device, locale);
        List<BehaviorAnalyticsMapper.ClickPointRow> points = mapper.clickPoints(
                normalizedRoute, query.startAt(), query.endAt(), query.device(), query.locale());
        long maxPointWeight = points.stream().mapToLong(BehaviorAnalyticsMapper.ClickPointRow::weight).max().orElse(1L);
        List<Map<String, Object>> normalizedPoints = points.stream().map(point -> linked(
                "x", point.x(), "y", point.y(),
                "weight", Math.round(point.weight() * 10_000d / maxPointWeight) / 10_000d)).toList();
        List<BehaviorAnalyticsMapper.ZoneRow> zoneRows = mapper.zones(
                normalizedRoute, query.startAt(), query.endAt(), query.device(), query.locale());
        long total = zoneRows.stream().mapToLong(BehaviorAnalyticsMapper.ZoneRow::count).sum();
        List<Map<String, Object>> zones = zoneRows.stream().map(row -> linked(
                "label", row.zone(),
                "cx", "TOP".equals(row.zone()) ? 0.5 : "BOTTOM".equals(row.zone()) ? 0.5 : 0.5,
                "cy", "TOP".equals(row.zone()) ? 0.13 : "BOTTOM".equals(row.zone()) ? 0.87 : 0.5,
                "share", total == 0 ? 0d : Math.round(row.count() * 10000d / total) / 10000d)).toList();
        return ApiResult.ok(linked("route", normalizedRoute, "titleZh", page.titleZh(), "depth", normalizedDepth, "points", normalizedPoints, "zones", zones,
                "aggregated", true));
    }

    public ApiResult<Map<String, Object>> pageCatalog() {
        requireProductionL6Surface();
        List<BehaviorAnalyticsMapper.CatalogRow> pages = mapper.listCatalog();
        List<BehaviorAnalyticsMapper.CatalogRow> tracked = pages.stream().filter(BehaviorAnalyticsMapper.CatalogRow::tracked).toList();
        List<BehaviorAnalyticsMapper.CatalogRow> excluded = pages.stream().filter(row -> !row.tracked()).toList();
        return ApiResult.ok(linked("totalPages", pages.size(), "trackedCount", tracked.size(),
                "pageTree", tracked, "excludedPages", excluded, "source", "uniapp:src/pages.json+i18n.headerTitles"));
    }

    public byte[] exportBehavior(String window, String device, String locale, String depth, String sort) {
        // Must precede queries, audit, and outbox: acceptance admins cannot
        // use the production export route to create production side effects.
        requireProductionL6Surface();
        Query query = query(window, device, locale);
        String normalizedDepth = normalizeDepth(depth);
        List<BehaviorAnalyticsMapper.ActivityRow> rows = new ArrayList<>(
                mapper.activity(query.startAt(), query.endAt(), query.device(), query.locale(), normalizedDepth));
        sortActivity(rows, sort);
        require(!rows.isEmpty(), "L6_EXPORT_EMPTY");
        Map<String, BehaviorAnalyticsMapper.CatalogRow> catalog = new LinkedHashMap<>();
        mapper.listCatalog().forEach(row -> catalog.put(row.route(), row));
        StringBuilder csv = new StringBuilder("route,title_zh,page_level,pv,uv,clicks,avg_dwell_ms,bounce_rate\n");
        for (BehaviorAnalyticsMapper.ActivityRow row : rows) {
            BehaviorAnalyticsMapper.CatalogRow page = catalog.get(row.route());
            csv.append(csv(row.route())).append(',').append(csv(page == null ? row.route() : page.titleZh())).append(',')
                    .append(page == null ? 3 : page.pageLevel()).append(',').append(row.pv()).append(',').append(row.uv()).append(',')
                    .append(row.clicks()).append(',').append(row.dwellMs()).append(',').append(row.bounceRate()).append('\n');
        }
        String exportId = "L6-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("admin.report_exported")
                .resourceType("BI_BEHAVIOR_AGGREGATE")
                .resourceId(exportId)
                .actorType("ADMIN")
                .result("SUCCESS")
                .riskLevel("LOW")
                .detail(linked("window", query.window(), "depth", normalizedDepth, "device", valueOrAll(query.device()),
                        "locale", valueOrAll(query.locale()), "rows", rows.size(), "containsPii", false))
                .build());
        outbox.publish("BI_REPORT", exportId, "admin.report_exported", linked(
                "reportId", exportId,
                "exportType", "BEHAVIOR_AGGREGATE",
                "scope", query.window() + "|" + normalizedDepth + "|" + valueOrAll(query.device()) + "|" + valueOrAll(query.locale()),
                "rowCount", rows.size(),
                "containsPii", false,
                "maskingPolicy", "AGGREGATED",
                "operator", AdminActorResolver.resolve("unknown"),
                "reason", "L6 behavior aggregate export",
                "format", "CSV"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Query query(String window, String device, String locale) {
        String rawWindow = text(window).toLowerCase(Locale.ROOT);
        String normalizedWindow = rawWindow.isEmpty() ? "7d" : rawWindow;
        require(Set.of("24h", "7d", "30d").contains(normalizedWindow), "L6_WINDOW_INVALID");
        LocalDateTime end = LocalDateTime.now(BUSINESS_ZONE);
        LocalDateTime start = "24h".equals(normalizedWindow) ? end.minusHours(24)
                : "30d".equals(normalizedWindow) ? end.minusDays(30) : end.minusDays(7);
        String normalizedDevice = "ALL".equalsIgnoreCase(text(device)) || text(device).isEmpty() ? null : normalizeDevice(device);
        String normalizedLocale = "ALL".equalsIgnoreCase(text(locale)) || text(locale).isEmpty() ? null : normalizeLocale(locale);
        return new Query(normalizedWindow, start, end, normalizedDevice, normalizedLocale);
    }

    private String normalizeDepth(String depth) {
        String normalized = text(depth).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return "ALL";
        require(Set.of("ALL", "L1", "L2", "L3").contains(normalized), "L6_DEPTH_INVALID");
        return normalized;
    }

    private void sortActivity(List<BehaviorAnalyticsMapper.ActivityRow> rows, String sort) {
        String normalized = text(sort).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) normalized = "pv";
        require(Set.of("pv", "clicks", "dwellms", "dwell", "bouncerate", "bounce").contains(normalized),
                "L6_SORT_INVALID");
        Comparator<BehaviorAnalyticsMapper.ActivityRow> comparator = switch (normalized) {
            case "clicks" -> Comparator.comparingLong(BehaviorAnalyticsMapper.ActivityRow::clicks);
            case "dwellms", "dwell" -> Comparator.comparingLong(BehaviorAnalyticsMapper.ActivityRow::dwellMs);
            case "bouncerate", "bounce" -> Comparator.comparingDouble(BehaviorAnalyticsMapper.ActivityRow::bounceRate);
            case "pv" -> Comparator.comparingLong(BehaviorAnalyticsMapper.ActivityRow::pv);
            default -> throw new IllegalStateException("L6_SORT_UNREACHABLE");
        };
        rows.sort(comparator.reversed().thenComparing(BehaviorAnalyticsMapper.ActivityRow::route));
    }

    private String normalizeRoute(String value) {
        String route = text(value).split("[?#]", 2)[0];
        return ROUTE.matcher(route).matches() ? route : "";
    }

    private String normalizeDevice(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        require(DEVICES.contains(normalized), "L6_DEVICE_INVALID");
        return normalized;
    }

    private String normalizeLocale(String value) {
        String normalized = text(value);
        require("und".equals(normalized) || LOCALE.matcher(normalized).matches(), "L6_LOCALE_INVALID");
        return normalized;
    }

    private LocalDateTime normalizeClientTime(Long clientTs) {
        long now = System.currentTimeMillis();
        require(clientTs != null && Math.abs(now - clientTs) <= MAX_DWELL_MS, "L6_CLIENT_TIME_INVALID");
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(clientTs), BUSINESS_ZONE);
    }

    private String pseudonym(String namespace, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (pseudonymSecret + "\u001f" + namespace + "\u001f" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("L6_PSEUDONYM_FAILURE", ex);
        }
    }

    /**
     * MySQL named locks are connection-scoped.  The surrounding transaction
     * keeps mapper calls on that connection, so latest watermark, rate cap,
     * click throttle, unique fact claim and outbox finalization are one
     * per-session critical section.  The key is fixed-width and includes the
     * server environment plus Run before hashing, preventing cross-run lock
     * contention or user-controlled lock names.
     */
    private <T> T withProductionSessionAuthority(String sessionHash, Supplier<T> work) {
        String lockKey = sessionLockKey("PRODUCTION", "", sessionHash);
        if (!Integer.valueOf(1).equals(mapper.tryAcquireSessionLock(lockKey))) {
            throw new BizException(429, "L6_SESSION_LOCK_BUSY");
        }
        boolean releaseAfterTransaction = deferSessionLockRelease(() -> mapper.releaseSessionLock(lockKey));
        try {
            return work.get();
        } finally {
            if (!releaseAfterTransaction) mapper.releaseSessionLock(lockKey);
        }
    }

    private <T> T withSandboxSessionAuthority(String sessionHash, Supplier<T> work) {
        String lockKey = sessionLockKey("SANDBOX", acceptanceRunId, sessionHash);
        if (!Integer.valueOf(1).equals(sandboxMapper.tryAcquireSessionLock(lockKey))) {
            throw new BizException(429, "L6_SESSION_LOCK_BUSY");
        }
        boolean releaseAfterTransaction = deferSessionLockRelease(() -> sandboxMapper.releaseSessionLock(lockKey));
        try {
            return work.get();
        } finally {
            if (!releaseAfterTransaction) sandboxMapper.releaseSessionLock(lockKey);
        }
    }

    /** Keep the connection-scoped MySQL authority lock through commit or rollback. */
    private boolean deferSessionLockRelease(Runnable release) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                release.run();
            }
        });
        return true;
    }

    private String sessionLockKey(String sourceEnvironment, String runId, String sessionHash) {
        // SHA-256 hex is exactly 64 characters, the MySQL named-lock limit.
        return pseudonym("session-authority-lock", sourceEnvironment + "\u001f" + runId + "\u001f" + sessionHash);
    }

    private void requireValidAcceptanceRunId() {
        require(acceptanceRunId != null && acceptanceRunId.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"),
                "L6_ACCEPTANCE_RUN_ID_REQUIRED");
    }

    /** Hash every canonical field, so a reused clientEventId cannot alter any fact dimension. */
    private String fingerprint(String sourceEnvironment, String actorHash, String sessionHash, Long clientTs,
            String eventName, String route, String device, String locale, Long dwellMs, Double xNorm, Double yNorm,
            String zone, String elementId) {
        return pseudonym("event-fingerprint", String.join("\u001f", sourceEnvironment, actorHash, sessionHash,
                String.valueOf(clientTs), eventName, route, device, locale, String.valueOf(dwellMs),
                String.valueOf(xNorm), String.valueOf(yNorm), String.valueOf(zone), String.valueOf(elementId)));
    }

    private String observationToken(String runId, String actorHash, String sessionHash) {
        return pseudonym("acceptance-observation", String.join("\u001f", runId, actorHash, sessionHash));
    }

    private double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private boolean finiteUnit(Double value) {
        return value != null && Double.isFinite(value) && value >= 0d && value <= 1d;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private void requireClientEventFingerprint(String expected, String actual) {
        if (!expected.equals(actual)) throw new BizException(409, "L6_CLIENT_EVENT_ID_CONFLICT");
    }

    /** Production L6 endpoints are unavailable in every named non-production,
     * mixed, or unknown profile. An unprofiled runtime retains legacy/default
     * production behavior. Acceptance has its own conditional controller. */
    private void requireProductionL6Surface() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) return;
        require("PRODUCTION".equals(BehaviorAnalyticsAcceptanceProfileCondition
                .sourceEnvironmentFor(activeProfiles)), "L6_PRODUCTION_SURFACE_FORBIDDEN");
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String valueOrAll(String value) {
        return value == null ? "ALL" : value;
    }

    private String csv(String value) {
        return '"' + String.valueOf(value).replace("\"", "\"\"") + '"';
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private record Query(String window, LocalDateTime startAt, LocalDateTime endAt, String device, String locale) {}
}
