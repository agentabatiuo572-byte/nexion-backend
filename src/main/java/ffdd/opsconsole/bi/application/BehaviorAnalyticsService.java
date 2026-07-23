package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.bi.web.BehaviorEventRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BehaviorAnalyticsService {
    private static final Pattern ROUTE = Pattern.compile("^/pages/[a-z0-9-]+/[a-z0-9-]+$");
    private static final Pattern SESSION = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern ELEMENT = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(?:-[A-Z]{2})?$");
    private static final Set<String> EVENTS = Set.of("app.page_viewed", "app.element_clicked");
    private static final Set<String> DEVICES = Set.of("APP", "H5", "MP");
    private static final Set<String> ZONES = Set.of("TOP", "CONTENT", "BOTTOM");
    private static final long MAX_DWELL_MS = 86_400_000L;

    private final BehaviorAnalyticsMapper mapper;
    private final EventOutboxService outbox;
    private final AuditLogService auditLogService;
    @Value("${nexion.analytics.pseudonym-secret:${jwt.secret:nexion-l6-local-only}}")
    private final String pseudonymSecret;

    @Transactional
    public ApiResult<Map<String, Object>> ingest(Long userId, BehaviorEventRequest request) {
        require(userId != null && userId > 0, "USER_AUTH_REQUIRED");
        require(request != null && EVENTS.contains(request.eventName()), "L6_EVENT_NOT_ALLOWED");
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
            zone = yNorm < 0.25d ? "TOP" : yNorm > 0.75d ? "BOTTOM" : "CONTENT";
            require(ZONES.contains(requestedZone) && zone.equals(requestedZone), "L6_ZONE_INVALID");
            if (StringUtils.hasText(request.elementId())) {
                elementId = text(request.elementId()).toLowerCase(Locale.ROOT);
                require(ELEMENT.matcher(elementId).matches(), "L6_ELEMENT_ID_INVALID");
            }
            payload.put("x_norm", xNorm);
            payload.put("y_norm", yNorm);
            payload.put("zone", zone);
            if (elementId != null) payload.put("element_id", elementId);
        }

        String dedupeKey = pseudonym("event", request.eventName() + "|" + sessionHash + "|" + route + "|"
                + request.clientTs() + "|" + xNorm + "|" + yNorm + "|" + dwellMs);
        if (mapper.countByDedupeKey(dedupeKey) > 0) {
            return ApiResult.ok(linked("accepted", false, "duplicate", true));
        }
        long perMinuteLimit = "app.element_clicked".equals(request.eventName()) ? 180L : 120L;
        if (mapper.countRecent(sessionHash, request.eventName(), LocalDateTime.now().minusMinutes(1)) >= perMinuteLimit) {
            throw new BizException(429, "L6_EVENT_RATE_LIMITED");
        }
        if ("app.element_clicked".equals(request.eventName())) {
            LocalDateTime latest = mapper.latestEventAt(sessionHash, request.eventName());
            if (latest != null && latest.plusNanos(300_000_000L).isAfter(LocalDateTime.now())) {
                throw new BizException(429, "L6_CLICK_THROTTLED");
            }
        }
        String eventId = outbox.publishClientAnalyticsEvent(sessionHash, request.eventName(), payload);
        mapper.insertFact(new BehaviorAnalyticsMapper.BehaviorFactRow(
                eventId, dedupeKey, request.eventName(), sessionHash, actorHash, route, page.pageLevel(), page.parentL1(), page.parentL2(),
                dwellMs, xNorm, yNorm, zone, elementId, device, locale, occurredAt));
        return ApiResult.ok(linked("accepted", true, "eventId", eventId));
    }

    public ApiResult<Map<String, Object>> behavior(String window, String device, String locale, String depth, String sort) {
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
                "activity", rows,
                "dailyTrend", mapper.dailyTrend(query.startAt(), query.endAt(), query.device(), query.locale()),
                "weeklyTrend", mapper.weeklyTrend(query.startAt(), query.endAt(), query.device(), query.locale()),
                "privacy", linked("aggregatedOnly", true, "rawTextStored", false, "directUserIdStored", false),
                "sources", List.of("nx_behavior_event_fact", "nx_behavior_page_catalog", "nx_event_outbox")));
    }

    public ApiResult<Map<String, Object>> clickHeat(String route, String window, String device, String locale, String depth) {
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
        List<BehaviorAnalyticsMapper.CatalogRow> pages = mapper.listCatalog();
        List<BehaviorAnalyticsMapper.CatalogRow> tracked = pages.stream().filter(BehaviorAnalyticsMapper.CatalogRow::tracked).toList();
        List<BehaviorAnalyticsMapper.CatalogRow> excluded = pages.stream().filter(row -> !row.tracked()).toList();
        return ApiResult.ok(linked("totalPages", pages.size(), "trackedCount", tracked.size(),
                "pageTree", tracked, "excludedPages", excluded, "source", "uniapp:src/pages.json+i18n.headerTitles"));
    }

    public byte[] exportBehavior(String window, String device, String locale, String depth, String sort) {
        Query query = query(window, device, locale);
        String normalizedDepth = normalizeDepth(depth);
        List<BehaviorAnalyticsMapper.ActivityRow> rows = new ArrayList<>(
                mapper.activity(query.startAt(), query.endAt(), query.device(), query.locale(), normalizedDepth));
        sortActivity(rows, sort);
        Map<String, BehaviorAnalyticsMapper.CatalogRow> catalog = new LinkedHashMap<>();
        mapper.listCatalog().forEach(row -> catalog.put(row.route(), row));
        StringBuilder csv = new StringBuilder("route,title_zh,page_level,pv,uv,clicks,avg_dwell_ms,bounce_rate\n");
        for (BehaviorAnalyticsMapper.ActivityRow row : rows) {
            BehaviorAnalyticsMapper.CatalogRow page = catalog.get(row.route());
            csv.append(csv(row.route())).append(',').append(csv(page == null ? row.route() : page.titleZh())).append(',')
                    .append(page == null ? 3 : page.pageLevel()).append(',').append(row.pv()).append(',').append(row.uv()).append(',')
                    .append(row.clicks()).append(',').append(row.dwellMs()).append(',').append(row.bounceRate()).append('\n');
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("admin.report_exported")
                .resourceType("BI_BEHAVIOR_AGGREGATE")
                .resourceId("L6-" + query.window())
                .actorType("ADMIN")
                .result("SUCCESS")
                .riskLevel("LOW")
                .detail(linked("window", query.window(), "depth", normalizedDepth, "device", valueOrAll(query.device()),
                        "locale", valueOrAll(query.locale()), "rows", rows.size(), "containsPii", false))
                .build());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Query query(String window, String device, String locale) {
        String normalizedWindow = switch (text(window).toLowerCase(Locale.ROOT)) {
            case "24h", "30d" -> text(window).toLowerCase(Locale.ROOT);
            default -> "7d";
        };
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = "24h".equals(normalizedWindow) ? end.minusHours(24)
                : "30d".equals(normalizedWindow) ? end.minusDays(30) : end.minusDays(7);
        String normalizedDevice = "ALL".equalsIgnoreCase(text(device)) || text(device).isEmpty() ? null : normalizeDevice(device);
        String normalizedLocale = "ALL".equalsIgnoreCase(text(locale)) || text(locale).isEmpty() ? null : normalizeLocale(locale);
        return new Query(normalizedWindow, start, end, normalizedDevice, normalizedLocale);
    }

    private String normalizeDepth(String depth) {
        return switch (text(depth).toUpperCase(Locale.ROOT)) {
            case "L1", "L2", "L3" -> text(depth).toUpperCase(Locale.ROOT);
            default -> "ALL";
        };
    }

    private void sortActivity(List<BehaviorAnalyticsMapper.ActivityRow> rows, String sort) {
        Comparator<BehaviorAnalyticsMapper.ActivityRow> comparator = switch (text(sort).toLowerCase(Locale.ROOT)) {
            case "clicks" -> Comparator.comparingLong(BehaviorAnalyticsMapper.ActivityRow::clicks);
            case "dwellms", "dwell" -> Comparator.comparingLong(BehaviorAnalyticsMapper.ActivityRow::dwellMs);
            case "bouncerate", "bounce" -> Comparator.comparingDouble(BehaviorAnalyticsMapper.ActivityRow::bounceRate);
            default -> Comparator.comparingLong(BehaviorAnalyticsMapper.ActivityRow::pv);
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
        long value = clientTs == null || Math.abs(now - clientTs) > MAX_DWELL_MS ? now : clientTs;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault());
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

    private double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private boolean finiteUnit(Double value) {
        return value != null && Double.isFinite(value) && value >= 0d && value <= 1d;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
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
