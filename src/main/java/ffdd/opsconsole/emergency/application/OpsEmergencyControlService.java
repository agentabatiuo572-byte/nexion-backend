package ffdd.opsconsole.emergency.application;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.ContentNotificationDispatchFacade;
import ffdd.opsconsole.content.facade.NotificationEmergencyDispatchResult;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.GeoCountryListRequest;
import ffdd.opsconsole.emergency.dto.GeoEdgeJudgeRequest;
import ffdd.opsconsole.emergency.dto.GeoEmergencyBlockRequest;
import ffdd.opsconsole.emergency.dto.GeoEndpointCountriesRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookCreateRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.emergency.dto.SopStepConfirmationRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookUpdateRequest;
import ffdd.opsconsole.emergency.dto.TamperAlertConfigRequest;
import ffdd.opsconsole.emergency.dto.TamperReportRequest;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsEmergencyControlService implements ffdd.opsconsole.platform.domain.AuditReplayable {
    private static final Pattern ISO_COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern SOP_SLA_MINUTES = Pattern.compile("^(\\d{1,4})\\s*分钟$");
    private static final Pattern SOP_SLA_HOURS = Pattern.compile("^(?:≤|<=)?\\s*(\\d{1,2})\\s*h$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Set<String> GEO_TRIGGER_BASES = Set.of("监管点名", "挤兑风险", "安全事件", "其他");
    private static final Set<String> GEO_IDEMPOTENCY_REJECTION_CODES = Set.of(
            "IDEMPOTENCY_RECLAIM_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT",
            "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH", "IDEMPOTENCY_REQUEST_IN_PROGRESS",
            "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_REQUEST_HASH_REQUIRED",
            "IDEMPOTENCY_SCOPE_TOO_LONG", "IDEMPOTENCY_RESPONSE_DESERIALIZE_FAILED");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };
    private static final String GROUP_GEO_BLOCK = "admin_geo_block";
    private static final String GROUP_TAMPER = "admin_tamper";
    private static final String GROUP_SOP = "admin_sop";
    private static final String GEO_J4_BLOCK_REQUIRED = "emergency.geo.j4.block.required";
    private static final String GEO_EDGE_SOURCE = "emergency.geo.edgeJudgeSource";
    private static final String GEO_EDGE_FALLBACK_SOURCE = "emergency.geo.edgeJudgeFallbackSource";
    private static final String GEO_EDGE_FALLBACK_UNTIL = "emergency.geo.edgeJudgeFallbackUntilEpochMs";
    private static final long GEO_EDGE_FALLBACK_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final String TAMPER_THRESHOLD = "emergency.tamper.alert.threshold";
    private static final String TAMPER_FEED_K4 = "emergency.tamper.alert.feedK4";
    private static final int TAMPER_REPORT_MAX_ACCOUNTS = 10_000;
    private static final int SOP_EXECUTION_HISTORY_MAX_ROWS = 20;
    private static final int SOP_EXECUTION_HISTORY_MAX_BYTES = 60_000;
    private static final int SOP_EXECUTION_SEQUENCE_MAX_STEPS = 40;
    private static final int SOP_EXECUTION_TEXT_MAX_CHARS = 500;
    private static final Set<String> SOP_SCENES = Set.of("监管点名", "资金异常", "数据泄露", "舆情挤兑", "技术故障");
    private static final Set<String> SOP_OWNERS = Set.of("风控", "合规审计", "超管", "财务", "财务主管");
    private static final Set<String> SOP_EXECUTABLE_DOMAINS = Set.of("J1", "I3");
    private static final String SOP_J1_WITHDRAW_ACTION = "熔断提现通道";
    private static final String SOP_J1_GENESIS_ACTION = "熔断 Genesis 交易";
    private static final String SOP_I3_NOTIFY_ACTION = "发送通知模板";
    private static final Duration J4_EXECUTION_RECOVERY_LEASE = Duration.ofMinutes(2);
    private static final Set<String> SOP_RECOVERY_MARKERS = Set.of(
            "恢复", "解封", "放行", "开启", "启用", "restore", "release", "enable", "resume");
    private static final Set<String> J1_CANONICAL_GATE_SETTINGS = Set.of(
            "killswitch.withdraw", "killswitch.staking", "killswitch.genesis",
            "killswitch.exchange", "killswitch.trial");

    private final PlatformConfigFacade configFacade;
    private final ContentNotificationDispatchFacade notificationDispatchFacade;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final EmergencyControlRepository emergencyRepository;
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper;
    private final ffdd.opsconsole.emergency.application.OpsKillSwitchService killSwitchService;
    private final EventOutboxService outboxService;
    private final EventConsumerDeliveryService consumerDeliveryService;
    private final AdminIdempotencyService idempotencyService;
    private final GeoEdgeHealthMonitor geoEdgeHealthMonitor;
    private final PlatformTransactionManager transactionManager;

    public ApiResult<Map<String, Object>> geoBlockOverview() {
        Map<String, Map<String, Object>> impacts = countryImpactMap();
        List<Map<String, Object>> countries = countryViews(impacts);
        List<Map<String, Object>> blocked = countries.stream()
                .filter(row -> "blocked".equals(row.get("status")))
                .toList();
        List<Map<String, Object>> limited = countries.stream()
                .filter(row -> "limited".equals(row.get("status")))
                .toList();
        List<Map<String, Object>> hits = geoHits();
        int totalHits = hits.stream().mapToInt(row -> intValue(row.get("count"))).sum();
        String edgeSource = emergencyRepository.settingValue(GEO_EDGE_SOURCE)
                .orElse(GeoEdgeSourceRegistry.DEFAULT_SOURCE);
        Map<String, Object> response = map(
                "domain", "J2",
                "blocked", blocked,
                "limited", limited,
                "countries", countries,
                "countryOptions", countryOptions(impacts),
                "recentChanges", emergencyRepository.geoRecentChanges(),
                "endpoints", geoEndpoints(),
                "hits", hits,
                "edge", geoEdge(),
                "stats", map(
                        "blockedCount", blocked.size(),
                        "limitedCount", limited.size(),
                        "totalHits", totalHits,
                        "health", edgeHealthLabel(geoEdgeHealthMonitor.snapshot(edgeSource)),
                        "confirmSlaMins", activeValue("ops.J.emergency.confirmSlaMins").orElse("")),
                "sources", List.of("nx_emergency_geo_country_policy", "nx_emergency_geo_endpoint_policy", "nx_emergency_geo_block_event", "nx_emergency_control_setting"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> geoBlockAlerts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        Set<String> deliveredEventIds = consumerDeliveryService
                .listByAggregate("KILL_SWITCH", "geo-block", 100).stream()
                .filter(delivery -> GeoPolicyAdminAlertConsumer.CONSUMER_GROUP.equals(delivery.getConsumerGroup()))
                .filter(delivery -> "SUCCESS".equals(delivery.getStatus()))
                .map(EventConsumerDelivery::getEventId)
                .collect(Collectors.toSet());
        List<Map<String, Object>> alerts = outboxService.listByAggregate("KILL_SWITCH", "geo-block", 20).stream()
                .filter(message -> "ADMIN_KILLSWITCH_TOGGLED".equals(message.getEventType()))
                .filter(message -> "PUBLISHED".equals(message.getStatus()))
                .filter(message -> deliveredEventIds.contains(message.getEventId()))
                .filter(message -> message.getCreatedAt() == null || !message.getCreatedAt().isBefore(cutoff))
                .map(this::geoBlockAlert)
                .flatMap(Optional::stream)
                .limit(5)
                .toList();
        return ApiResult.ok(map("alerts", alerts, "source", "nx_event_outbox", "audienceRole", "SUPER_ADMIN"));
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> geoBlockAlert(EventOutboxMessage message) {
        try {
            Object decoded = objectMapper.readValue(message.getPayload(), Object.class);
            if (!(decoded instanceof Map<?, ?> raw)) {
                return Optional.empty();
            }
            Map<String, Object> payload = (Map<String, Object>) raw;
            if (!"SUPER_ADMIN".equals(stringValue(payload.get("audienceRole"), ""))) {
                return Optional.empty();
            }
            String scope = stringValue(payload.get("scope"), "策略");
            String target = stringValue(payload.get("target"), "地域封锁");
            String operator = stringValue(payload.get("operator"), "system");
            String reason = stringValue(payload.get("reason"), "未填写原因");
            return Optional.of(map(
                    "id", "J2-" + message.getEventId(),
                    "domain", "J2",
                    "level", "mid",
                    "title", "J2 地域策略已变更 · " + scope,
                    "hint", target + " · " + operator + " · " + reason,
                    "occurredAt", stringValue(payload.get("occurredAt"),
                            message.getCreatedAt() == null ? "" : message.getCreatedAt().toString())));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public ApiResult<Map<String, Object>> tamperConfigAlerts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        Set<String> deliveredEventIds = consumerDeliveryService
                .listByAggregate("TAMPER_ALERT_CONFIG", "default", 100).stream()
                .filter(delivery -> TamperConfigAdminAlertConsumer.CONSUMER_GROUP.equals(delivery.getConsumerGroup()))
                .filter(delivery -> "SUCCESS".equals(delivery.getStatus()))
                .map(EventConsumerDelivery::getEventId)
                .collect(Collectors.toSet());
        List<Map<String, Object>> alerts = outboxService
                .listByAggregate("TAMPER_ALERT_CONFIG", "default", 20).stream()
                .filter(message -> "ADMIN_J3_TAMPER_CONFIG_CHANGED".equals(message.getEventType()))
                .filter(message -> "PUBLISHED".equals(message.getStatus()))
                .filter(message -> deliveredEventIds.contains(message.getEventId()))
                .filter(message -> message.getCreatedAt() == null || !message.getCreatedAt().isBefore(cutoff))
                .map(this::tamperConfigAlert)
                .flatMap(Optional::stream)
                .limit(5)
                .toList();
        return ApiResult.ok(map("alerts", alerts, "source", "nx_event_outbox", "audienceRole", "SUPER_ADMIN"));
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> tamperConfigAlert(EventOutboxMessage message) {
        try {
            Object decoded = objectMapper.readValue(message.getPayload(), Object.class);
            if (!(decoded instanceof Map<?, ?> raw)) {
                return Optional.empty();
            }
            Map<String, Object> payload = (Map<String, Object>) raw;
            if (!"SUPER_ADMIN".equals(stringValue(payload.get("audienceRole"), ""))) {
                return Optional.empty();
            }
            Map<?, ?> before = payload.get("before") instanceof Map<?, ?> beforeValue ? beforeValue : Map.of();
            Map<?, ?> after = payload.get("after") instanceof Map<?, ?> afterValue ? afterValue : Map.of();
            String thresholdChange = stringValue(before.get("threshold"), "?")
                    + "→" + stringValue(after.get("threshold"), "?") + " 次/24h";
            String k4Change = booleanStateLabel(before.get("feedK4"))
                    + "→" + booleanStateLabel(after.get("feedK4"));
            String operator = stringValue(payload.get("operator"), "system");
            String reason = stringValue(payload.get("reason"), "未填写原因");
            return Optional.of(map(
                    "id", "J3-" + message.getEventId(),
                    "domain", "J3",
                    "level", "mid",
                    "title", "J3 篡改告警配置已变更",
                    "hint", "阈值 " + thresholdChange + " · K4 " + k4Change + " · " + operator + " · " + reason,
                    "occurredAt", stringValue(payload.get("occurredAt"),
                            message.getCreatedAt() == null ? "" : message.getCreatedAt().toString())));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateGeoCountry(String countryCode, String idempotencyKey, GeoCountryStatusRequest request) {
        String operator = authenticatedOperator(request == null ? null : request.operator());
        Object attempted = request == null ? Map.of() : map(
                "status", request.status(), "expectedStatus", request.expectedStatus(),
                "triggerBasis", request.triggerBasis());
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectGeoCommand(guard.getCode(), guard.getMessage(), "GEO_COUNTRY",
                    stringValue(countryCode, "unknown"), operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request == null ? null : request.reason());
        }
        String cc;
        String status;
        String expectedStatus;
        try {
            cc = normalizeCountry(countryCode);
            status = normalizeGeoStatus(request.status());
            expectedStatus = StringUtils.hasText(request.expectedStatus())
                    ? normalizeGeoStatus(request.expectedStatus())
                    : null;
        } catch (IllegalArgumentException ex) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage(), "GEO_COUNTRY",
                    stringValue(countryCode, "unknown"), operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        if (expectedStatus == null) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GEO_COUNTRY_EXPECTED_STATE_REQUIRED",
                    "GEO_COUNTRY", cc, operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        return idempotentGeo(
                "J2_COUNTRY:" + cc,
                idempotencyKey,
                requestHash(cc, status, expectedStatus, request.triggerBasis(), request.reason(), operator),
                "GEO_COUNTRY", cc, operator, "HIGH", expectedStatus, status, request.reason(),
                () -> updateGeoCountryOnce(cc, status, expectedStatus, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> updateGeoCountryOnce(
            String cc,
            String status,
            String expectedStatus,
            String idempotencyKey,
            GeoCountryStatusRequest request,
            String operator) {
        emergencyRepository.lockGeoCountryMutations();
        String beforeStatus = countryStatus(cc);
        if (expectedStatus != null && !expectedStatus.equals(beforeStatus)) {
            return rejectGeoCommand(409, "GEO_COUNTRY_STATE_CONFLICT", "GEO_COUNTRY", cc, operator, "HIGH",
                    beforeStatus, status, idempotencyKey, request.reason());
        }
        if (beforeStatus.equals(status)) {
            return rejectGeoCommand(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "GEO_COUNTRY_STATE_UNCHANGED",
                    "GEO_COUNTRY", cc, operator, "HIGH", beforeStatus, status, idempotencyKey, request.reason());
        }
        if ("blocked".equals(beforeStatus) && "limited".equals(status)) {
            return rejectGeoCommand(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "GEO_BLOCKED_CANNOT_BECOME_LIMITED",
                    "GEO_COUNTRY", cc, operator, "HIGH", beforeStatus, status, idempotencyKey, request.reason());
        }
        if (statusRank(status) > statusRank(beforeStatus)) {
            ApiResult<Map<String, Object>> triggerGuard = requireGeoTriggerBasis(request.triggerBasis());
            if (triggerGuard != null) {
                return rejectGeoCommand(triggerGuard.getCode(), triggerGuard.getMessage(), "GEO_COUNTRY", cc, operator,
                        "HIGH", beforeStatus, status, idempotencyKey, request.reason());
            }
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("J", "country", cc) > 0) {
            return rejectGeoCommand(409, "OBJECT_LOCKED_BY_A2", "GEO_COUNTRY", cc, operator, "HIGH",
                    beforeStatus, status, idempotencyKey, request.reason());
        }
        List<String> blockedBefore = countryCodesByStatus("blocked");
        List<String> limitedBefore = countryCodesByStatus("limited");
        writeCountryStatus(cc, status, request.reason().trim(), operator);
        String eventId = publishGeoChange("country", cc, beforeStatus, status, operator, request.reason(), request.triggerBasis());
        auditRequired("J2_GEO_COUNTRY_STATUS_CHANGED", "GEO_COUNTRY", cc, operator, "HIGH", map(
                "country", cc,
                "before", beforeStatus,
                "after", status,
                "activeCountriesBefore", blockedBefore,
                "activeCountriesAfter", countryCodesByStatus("blocked"),
                "limitedCountriesBefore", limitedBefore,
                "limitedCountriesAfter", countryCodesByStatus("limited"),
                "triggerBasis", stringValue(request.triggerBasis(), ""),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcastEventId", eventId));
        Map<String, Object> response = geoBlockOverview().getData();
        response.put("updated", countryView(cc));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> replaceGeoCountryList(
            String pathStatus,
            String idempotencyKey,
            GeoCountryListRequest request) {
        String operator = authenticatedOperator(request == null ? null : request.operator());
        Object attempted = request == null ? Map.of() : map(
                "status", request.status(), "countries", request.countries(),
                "expectedCountries", request.expectedCountries(), "triggerBasis", request.triggerBasis());
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectGeoCommand(guard.getCode(), guard.getMessage(), "GEO_COUNTRY_LIST",
                    stringValue(pathStatus, "unknown"), operator, "CRITICAL", "NOT_EVALUATED", attempted,
                    idempotencyKey, request == null ? null : request.reason());
        }
        if (request.expectedCountries() == null) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GEO_COUNTRY_LIST_EXPECTED_STATE_REQUIRED",
                    "GEO_COUNTRY_LIST", stringValue(pathStatus, "unknown"), operator, "CRITICAL",
                    "NOT_EVALUATED", attempted, idempotencyKey, request.reason());
        }
        String status;
        List<String> target;
        List<String> expected;
        try {
            status = normalizeGeoStatus(StringUtils.hasText(pathStatus) ? pathStatus : request.status());
            target = normalizeCountries(request.countries());
            expected = normalizeCountries(request.expectedCountries());
        } catch (IllegalArgumentException ex) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage(), "GEO_COUNTRY_LIST",
                    stringValue(pathStatus, "unknown"), operator, "CRITICAL", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        if (!Set.of("blocked", "limited").contains(status)) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GEO_COUNTRY_LIST_STATUS_INVALID",
                    "GEO_COUNTRY_LIST", status, operator, "CRITICAL", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        return idempotentGeo(
                "J2_COUNTRY_LIST:" + status,
                idempotencyKey,
                requestHash(status, String.join(",", target), String.join(",", expected), request.triggerBasis(), request.reason(), operator),
                "GEO_COUNTRY_LIST", status, operator, "CRITICAL", expected, target, request.reason(),
                () -> replaceGeoCountryListOnce(status, target, expected, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> replaceGeoCountryListOnce(
            String status,
            List<String> target,
            List<String> expected,
            String idempotencyKey,
            GeoCountryListRequest request,
            String operator) {
        emergencyRepository.lockGeoCountryMutations();
        List<String> before = countryCodesByStatus(status);
        if (!before.equals(expected)) {
            return rejectGeoCommand(409, "GEO_COUNTRY_LIST_CONFLICT", "GEO_COUNTRY_LIST", status, operator, "CRITICAL",
                    before, target, idempotencyKey, request.reason());
        }
        List<String> added = target.stream().filter(country -> !before.contains(country)).toList();
        List<String> removed = before.stream().filter(country -> !target.contains(country)).toList();
        if (added.isEmpty() && removed.isEmpty()) {
            return rejectGeoCommand(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "GEO_COUNTRY_LIST_UNCHANGED",
                    "GEO_COUNTRY_LIST", status, operator, "CRITICAL", before, target, idempotencyKey, request.reason());
        }
        if ("limited".equals(status)) {
            List<String> blockedTargets = target.stream()
                    .filter(country -> "blocked".equals(countryStatus(country)))
                    .toList();
            if (!blockedTargets.isEmpty()) {
                return rejectGeoCommand(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "GEO_BLOCKED_CANNOT_BECOME_LIMITED",
                        "GEO_COUNTRY_LIST", status, operator, "CRITICAL", before, target, idempotencyKey, request.reason());
            }
        }
        if ("blocked".equals(status) && !added.isEmpty()) {
            ApiResult<Map<String, Object>> triggerGuard = requireGeoTriggerBasis(request.triggerBasis());
            if (triggerGuard != null) {
                return rejectGeoCommand(triggerGuard.getCode(), triggerGuard.getMessage(), "GEO_COUNTRY_LIST", status,
                        operator, "CRITICAL", before, target, idempotencyKey, request.reason());
            }
        }
        List<String> conflicts = java.util.stream.Stream.concat(added.stream(), removed.stream())
                .filter(country -> !A2ReplayContext.isReplaying()
                        && lockMapper.countActiveByTarget("J", "country", country) > 0)
                .distinct()
                .toList();
        if (!conflicts.isEmpty()) {
            return rejectGeoCommand(409, "GEO_COUNTRY_LIST_CONFLICT", "GEO_COUNTRY_LIST", status, operator, "CRITICAL",
                    before, target, idempotencyKey, request.reason());
        }

        List<String> blockedBefore = countryCodesByStatus("blocked");
        List<String> limitedBefore = countryCodesByStatus("limited");
        for (String country : added) {
            writeCountryStatus(country, status, request.reason().trim(), operator);
        }
        for (String country : removed) {
            writeCountryStatus(country, "allowed", request.reason().trim(), operator);
        }
        List<String> after = countryCodesByStatus(status);
        String eventId = publishGeoChange("country-list", status, before, after, operator, request.reason(), request.triggerBasis());
        auditRequired("J2_GEO_COUNTRY_LIST_CHANGED", "GEO_COUNTRY_LIST", status, operator, "CRITICAL", map(
                "status", status,
                "before", before,
                "after", after,
                "added", added,
                "removed", removed,
                "activeCountriesBefore", blockedBefore,
                "activeCountriesAfter", countryCodesByStatus("blocked"),
                "limitedCountriesBefore", limitedBefore,
                "limitedCountriesAfter", countryCodesByStatus("limited"),
                "triggerBasis", stringValue(request.triggerBasis(), ""),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcastEventId", eventId));
        Map<String, Object> response = geoBlockOverview().getData();
        response.put("updated", map("status", status, "countries", after, "added", added, "removed", removed));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateGeoEndpoint(String endpointKey, String idempotencyKey, GeoEndpointCountriesRequest request) {
        String operator = authenticatedOperator(request == null ? null : request.operator());
        Object attempted = request == null ? Map.of() : map(
                "mode", request.mode(), "countries", request.countries(),
                "expectedMode", request.expectedMode(), "expectedCountries", request.expectedCountries());
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectGeoCommand(guard.getCode(), guard.getMessage(), "GEO_ENDPOINT",
                    stringValue(endpointKey, "unknown"), operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request == null ? null : request.reason());
        }
        Map<String, Object> endpoint;
        List<String> countries;
        List<String> expectedCountries;
        String expectedMode;
        try {
            endpoint = endpointCatalog(endpointKey);
            countries = normalizeCountries(request.countries());
            expectedCountries = normalizeCountries(request.expectedCountries());
            expectedMode = StringUtils.hasText(request.expectedMode())
                    ? request.expectedMode().trim().toLowerCase(Locale.ROOT)
                    : null;
        } catch (IllegalArgumentException ex) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage(), "GEO_ENDPOINT",
                    stringValue(endpointKey, "unknown"), operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        if (!"ACTIVE".equalsIgnoreCase(stringValue(endpoint.get("status"), ""))) {
            return rejectGeoCommand(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "GEO_ENDPOINT_NOT_CONFIGURABLE",
                    "GEO_ENDPOINT", stringValue(endpointKey, "unknown"), operator, "HIGH",
                    "NOT_EVALUATED", attempted, idempotencyKey, request.reason());
        }
        String mode = StringUtils.hasText(request.mode()) ? request.mode().trim().toLowerCase(Locale.ROOT) : "explicit";
        if (!Set.of("explicit", "derived").contains(mode)) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GEO_ENDPOINT_MODE_INVALID",
                    "GEO_ENDPOINT", stringValue(endpointKey, "unknown"), operator, "HIGH",
                    "NOT_EVALUATED", attempted, idempotencyKey, request.reason());
        }
        if (expectedMode == null || !Set.of("explicit", "derived").contains(expectedMode)
                || request.expectedCountries() == null) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GEO_ENDPOINT_EXPECTED_STATE_REQUIRED",
                    "GEO_ENDPOINT", stringValue(endpointKey, "unknown"), operator, "HIGH",
                    "NOT_EVALUATED", attempted, idempotencyKey, request.reason());
        }
        if ("explicit".equals(mode) && countries.isEmpty()) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GEO_ENDPOINT_COUNTRIES_REQUIRED",
                    "GEO_ENDPOINT", stringValue(endpointKey, "unknown"), operator, "HIGH",
                    "NOT_EVALUATED", attempted, idempotencyKey, request.reason());
        }
        if ("derived".equals(mode)) {
            countries = List.of();
        }
        String normalizedKey = stringValue(endpoint.get("endpointKey"), "");
        List<String> targetCountries = countries;
        return idempotentGeo(
                "J2_ENDPOINT:" + normalizedKey,
                idempotencyKey,
                requestHash(normalizedKey, mode, String.join(",", targetCountries), expectedMode,
                        String.join(",", expectedCountries), request.reason(), operator),
                "GEO_ENDPOINT", normalizedKey, operator, "HIGH",
                map("mode", expectedMode, "countries", expectedCountries),
                map("mode", mode, "countries", targetCountries), request.reason(),
                () -> updateGeoEndpointOnce(endpoint, mode, targetCountries, expectedMode, expectedCountries,
                        idempotencyKey, request.reason(), operator));
    }

    private ApiResult<Map<String, Object>> updateGeoEndpointOnce(
            Map<String, Object> endpoint,
            String mode,
            List<String> countries,
            String expectedMode,
            List<String> expectedCountries,
            String idempotencyKey,
            String reason,
            String operator) {
        String endpointKey = stringValue(endpoint.get("endpointKey"), "");
        emergencyRepository.lockGeoEndpointMutation(endpointKey);
        Map<String, Object> before = geoEndpoints().stream()
                .filter(row -> endpointKey.equals(row.get("key")))
                .findFirst()
                .orElse(Map.of());
        String beforeMode = stringValue(before.get("source"), "derived");
        List<String> beforeCountries = before.get("countries") instanceof List<?> values
                ? values.stream().map(String::valueOf).sorted().toList()
                : List.of();
        Map<String, Object> beforeState = map("mode", beforeMode, "countries", beforeCountries);
        Map<String, Object> afterState = map("mode", mode, "countries", countries);
        if (!expectedMode.equals(beforeMode) || !expectedCountries.equals(beforeCountries)) {
            return rejectGeoCommand(409, "GEO_ENDPOINT_STATE_CONFLICT", "GEO_ENDPOINT", endpointKey, operator, "HIGH",
                    beforeState, afterState, idempotencyKey, reason);
        }
        if (mode.equals(beforeMode) && countries.equals(beforeCountries)) {
            return rejectGeoCommand(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "GEO_ENDPOINT_STATE_UNCHANGED",
                    "GEO_ENDPOINT", endpointKey, operator, "HIGH", beforeState, afterState, idempotencyKey, reason);
        }
        emergencyRepository.replaceGeoEndpointPolicies(
                endpointKey,
                stringValue(endpoint.get("endpointPath"), ""),
                stringValue(endpoint.get("label"), ""),
                stringValue(endpoint.get("biz"), ""),
                stringValue(endpoint.get("domain"), ""),
                countries,
                mode,
                reason.trim(),
                operator);
        String eventId = publishGeoChange("endpoint", endpointKey, beforeState, afterState, operator, reason, null);
        auditRequired("J2_GEO_ENDPOINT_COUNTRIES_CHANGED", "GEO_ENDPOINT", endpointKey, operator, "HIGH", map(
                "endpointKey", endpointKey,
                "endpoint", endpoint.get("endpointPath"),
                "label", endpoint.get("label"),
                "before", beforeState,
                "after", afterState,
                "mode", mode,
                "countries", countries,
                "reason", reason.trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcastEventId", eventId));
        Map<String, Object> response = geoBlockOverview().getData();
        int hits = emergencyRepository.geoEndpointHits().getOrDefault(endpointKey, 0);
        Map<String, Object> updated = endpointView(endpoint, countries, mode, reason.trim(), hits);
        response.put("updated", updated);
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateGeoEdgeJudge(String idempotencyKey, GeoEdgeJudgeRequest request) {
        String operator = authenticatedOperator(request == null ? null : request.operator());
        Object attempted = request == null ? Map.of() : map(
                "source", request.source(), "expectedSource", request.expectedSource());
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectGeoCommand(guard.getCode(), guard.getMessage(), "GEO_EDGE", "edgeJudgeSource",
                    operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request == null ? null : request.reason());
        }
        if (!StringUtils.hasText(request.source())) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EDGE_JUDGE_SOURCE_REQUIRED",
                    "GEO_EDGE", "edgeJudgeSource", operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        String source = request.source().trim().toLowerCase(Locale.ROOT);
        if (!GeoEdgeSourceRegistry.supports(source)) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EDGE_JUDGE_SOURCE_INVALID",
                    "GEO_EDGE", "edgeJudgeSource", operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        if (!StringUtils.hasText(request.expectedSource())) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EDGE_JUDGE_EXPECTED_SOURCE_REQUIRED",
                    "GEO_EDGE", "edgeJudgeSource", operator, "HIGH", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        String expectedSource = request.expectedSource().trim().toLowerCase(Locale.ROOT);
        return idempotentGeo(
                "J2_EDGE_SOURCE",
                idempotencyKey,
                requestHash(source, expectedSource, request.reason(), operator),
                "GEO_EDGE", "edgeJudgeSource", operator, "HIGH", expectedSource, source, request.reason(),
                () -> {
                    emergencyRepository.lockGeoEdgeMutation();
                    String before = emergencyRepository.settingValue(GEO_EDGE_SOURCE)
                            .orElse(GeoEdgeSourceRegistry.DEFAULT_SOURCE)
                            .trim().toLowerCase(Locale.ROOT);
                    if (!expectedSource.equals(before)) {
                        return rejectGeoCommand(409, "EDGE_JUDGE_SOURCE_CONFLICT", "GEO_EDGE", "edgeJudgeSource", operator,
                                "HIGH", before, source, idempotencyKey, request.reason());
                    }
                    if (before.equals(source)) {
                        return rejectGeoCommand(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "EDGE_JUDGE_SOURCE_UNCHANGED",
                                "GEO_EDGE", "edgeJudgeSource", operator, "HIGH", before, source, idempotencyKey, request.reason());
                    }
                    if (!geoEdgeHealthMonitor.isSwitchReady(source)) {
                        return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EDGE_JUDGE_SOURCE_UNHEALTHY",
                                "GEO_EDGE", "edgeJudgeSource", operator, "HIGH", before, source, idempotencyKey, request.reason());
                    }
                    // The old source is a fallback only when it is still registered and currently
                    // switch-ready. Invalid, stale or degraded sources must never be advertised as protection.
                    boolean fallbackReady = GeoEdgeSourceRegistry.supports(before)
                            && geoEdgeHealthMonitor.isSwitchReady(before);
                    emergencyRepository.upsertSetting(GEO_EDGE_FALLBACK_SOURCE,
                            fallbackReady ? before : "", "STRING", GROUP_GEO_BLOCK,
                            fallbackReady ? "J2 edge source transition fallback" : "J2 edge fallback cleared", operator);
                    emergencyRepository.upsertSetting(GEO_EDGE_FALLBACK_UNTIL,
                            fallbackReady
                                    ? String.valueOf(System.currentTimeMillis() + GEO_EDGE_FALLBACK_WINDOW_MILLIS)
                                    : "0",
                            "LONG", GROUP_GEO_BLOCK,
                            fallbackReady ? "J2 edge source transition deadline" : "J2 edge fallback disabled", operator);
                    emergencyRepository.upsertSetting(GEO_EDGE_SOURCE, source, "STRING", GROUP_GEO_BLOCK, request.reason().trim(), operator);
                    String eventId = publishGeoChange("edge-source", "edgeJudgeSource", before, source, operator, request.reason(), null);
                    auditRequired("J2_GEO_EDGE_JUDGE_SOURCE_CHANGED", "GEO_EDGE", "edgeJudgeSource", operator, "HIGH", map(
                            "before", before,
                            "after", source,
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim(),
                            "broadcastEventId", eventId));
                    return geoBlockOverview();
                });
    }

    @Transactional
    public ApiResult<Map<String, Object>> emergencyGeoBlock(String idempotencyKey, GeoEmergencyBlockRequest request) {
        String operator = authenticatedOperator(request == null ? null : request.operator());
        Object attempted = request == null ? Map.of() : map(
                "countries", request.countries(), "triggerBasis", request.triggerBasis());
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectGeoCommand(guard.getCode(), guard.getMessage(), "GEO_COUNTRY_BATCH", "emergency-block",
                    operator, "CRITICAL", "NOT_EVALUATED", attempted,
                    idempotencyKey, request == null ? null : request.reason());
        }
        List<String> countries;
        try {
            countries = normalizeCountries(request.countries());
        } catch (IllegalArgumentException ex) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage(),
                    "GEO_COUNTRY_BATCH", "emergency-block", operator, "CRITICAL", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        if (countries.isEmpty()) {
            return rejectGeoCommand(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EMERGENCY_BLOCK_COUNTRIES_REQUIRED",
                    "GEO_COUNTRY_BATCH", "emergency-block", operator, "CRITICAL", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        ApiResult<Map<String, Object>> triggerGuard = requireGeoTriggerBasis(request.triggerBasis());
        if (triggerGuard != null) {
            return rejectGeoCommand(triggerGuard.getCode(), triggerGuard.getMessage(), "GEO_COUNTRY_BATCH",
                    String.join(",", countries), operator, "CRITICAL", "NOT_EVALUATED", attempted,
                    idempotencyKey, request.reason());
        }
        return idempotentGeo(
                "J2_EMERGENCY_BLOCK",
                idempotencyKey,
                requestHash(String.join(",", countries), request.triggerBasis(), request.reason(), operator),
                "GEO_COUNTRY_BATCH", String.join(",", countries), operator, "CRITICAL",
                countryCodesByStatus("blocked"), countries, request.reason(),
                () -> emergencyGeoBlockOnce(countries, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> emergencyGeoBlockOnce(
            List<String> countries,
            String idempotencyKey,
            GeoEmergencyBlockRequest request,
            String operator) {
        emergencyRepository.lockGeoCountryMutations();
        List<String> conflicts = countries.stream()
                .filter(country -> "blocked".equals(countryStatus(country))
                        || (!A2ReplayContext.isReplaying()
                        && lockMapper.countActiveByTarget("J", "country", country) > 0))
                .toList();
        if (!conflicts.isEmpty()) {
            return rejectGeoCommand(409, "GEO_EMERGENCY_BLOCK_CONFLICT", "GEO_COUNTRY_BATCH",
                    String.join(",", countries), operator, "CRITICAL",
                    map("blockedCountries", countryCodesByStatus("blocked"), "conflicts", conflicts), countries,
                    idempotencyKey, request.reason());
        }
        List<String> blockedBefore = countryCodesByStatus("blocked");
        List<String> limitedBefore = countryCodesByStatus("limited");
        for (String country : countries) {
            writeCountryStatus(country, "blocked", request.reason().trim(), operator);
        }
        String eventId = publishGeoChange("country-batch", String.join(",", countries), blockedBefore,
                countryCodesByStatus("blocked"), operator, request.reason(), request.triggerBasis());
        auditRequired("J2_GEO_EMERGENCY_BLOCK_CREATED", "GEO_COUNTRY_BATCH", String.join(",", countries), operator, "CRITICAL", map(
                "countries", countries,
                "emergency", true,
                "before", blockedBefore,
                "after", countryCodesByStatus("blocked"),
                "activeCountriesBefore", blockedBefore,
                "activeCountriesAfter", countryCodesByStatus("blocked"),
                "limitedCountriesBefore", limitedBefore,
                "limitedCountriesAfter", countryCodesByStatus("limited"),
                "triggerBasis", request.triggerBasis().trim(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcastEventId", eventId));
        Map<String, Object> response = geoBlockOverview().getData();
        response.put("updated", map("countries", countries, "status", "blocked", "emergency", true));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> tamperOverview() {
        return tamperOverview("24h", 1, 5);
    }

    public ApiResult<Map<String, Object>> tamperOverview(int accountPage, int accountPageSize) {
        return tamperOverview("24h", accountPage, accountPageSize);
    }

    @Transactional
    public ApiResult<Map<String, Object>> tamperOverview(String window, int accountPage, int accountPageSize) {
        LocalDateTime now = LocalDateTime.now();
        TamperRange range = tamperRange(window, now);
        if (range == null) {
            auditRejectedJ3("J3_TAMPER_OVERVIEW_REJECTED", "TAMPER_OVERVIEW", "invalid-window",
                    authenticatedOperator(null), "TAMPER_WINDOW_INVALID", Map.of(),
                    map("window", window), "", "读取篡改监控总览");
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TAMPER_WINDOW_INVALID");
        }
        Map<String, Object> alertConfig = tamperAlertConfig();
        int threshold = intValue(alertConfig.get("threshold"));
        int effectiveThreshold = scaledTamperThreshold(threshold, range.label());
        List<Map<String, Object>> paths = tamperPaths(range);
        int pageSize = accountPageSize <= 0 ? 5 : Math.min(accountPageSize, 50);
        int totalAccounts = Math.toIntExact(emergencyRepository.countTamperAccounts(
                range.startAt(), range.endAt(), effectiveThreshold));
        int pages = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                (totalAccounts + pageSize - 1L) / pageSize));
        int page = Math.max(1, Math.min(accountPage, pages));
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<Map<String, Object>> pagedAccounts = emergencyRepository.pageTamperAccounts(
                        range.startAt(), range.endAt(), effectiveThreshold, offset, pageSize).stream()
                .map(this::tamperAccountView)
                .toList();
        int totalBlocked = paths.stream().mapToInt(row -> intValue(row.get("count"))).sum();
        TamperRange previousRange = range.previous();
        int previousBlocked = tamperPaths(previousRange).stream()
                .mapToInt(row -> intValue(row.get("count")))
                .sum();
        Double deltaPrevPct;
        if (previousBlocked == 0) {
            // There is no meaningful percentage base when a newly observed window follows zero events.
            deltaPrevPct = totalBlocked == 0 ? 0D : null;
        } else {
            deltaPrevPct = Math.round(((totalBlocked - previousBlocked) * 10_000D / previousBlocked)) / 100D;
        }
        Map<String, Object> trend = tamperTrend(now);
        int sevenDay = mapListValues((Map<?, ?>) trend.get("7d"), "points")
                .stream()
                .mapToInt(this::intValue)
                .sum();
        int sevenDayAlertAccounts = "7d".equals(range.label())
                ? totalAccounts
                : Math.toIntExact(emergencyRepository.countTamperAccounts(
                        now.minusDays(7), now, scaledTamperThreshold(threshold, "7d")));
        Map<String, Object> sevenDayPreviewByThreshold = sevenDayThresholdPreview(now);
        Map<String, Object> selectedAlertConfig = new LinkedHashMap<>(alertConfig);
        selectedAlertConfig.put("effectiveThreshold", effectiveThreshold);
        selectedAlertConfig.put("effectiveLabel", effectiveThreshold + " 次 / " + range.label());
        selectedAlertConfig.put("sevenDayAlertAccounts", sevenDayAlertAccounts);
        selectedAlertConfig.put("sevenDayPreviewByThreshold", sevenDayPreviewByThreshold);
        Map<String, Object> response = map(
                "domain", "J3",
                "window", range.label(),
                "hasData", totalBlocked > 0,
                "stats", map(
                        "totalBlocked", totalBlocked,
                        "previousWindowBlocked", previousBlocked,
                        "deltaPrevPct", deltaPrevPct,
                        "highFrequencyAccounts", totalAccounts,
                        "sevenDayBlocked", sevenDay,
                        "lossUsd", null),
                "trend", trend,
                "paths", paths,
                "accounts", pagedAccounts,
                "accountPage", map(
                        "page", page,
                        "pageSize", pageSize,
                        "total", totalAccounts,
                        "pages", pages,
                        "hasPrev", page > 1,
                        "hasNext", page < pages),
                "coverage", TamperCoverageRegistry.snapshot(),
                "alertConfig", selectedAlertConfig,
                "sources", List.of("nx_emergency_tamper_event", "nx_emergency_control_setting"));
        auditRequired("J3_TAMPER_OVERVIEW_VIEWED", "TAMPER_OVERVIEW", range.label(),
                authenticatedOperator(null), "LOW", map(
                        "window", range.label(),
                        "accountPage", page,
                        "accountPageSize", pageSize,
                        "eventCount", totalBlocked,
                        "coverage", TamperCoverageRegistry.snapshot()));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateTamperAlertConfig(String idempotencyKey, TamperAlertConfigRequest request) {
        String operator = authenticatedOperator(request == null ? null : request.operator());
        Object attempted = map(
                "threshold", request == null ? null : request.threshold(),
                "feedK4", request == null ? null : request.feedK4(),
                "expectedThreshold", request == null ? null : request.expectedThreshold(),
                "expectedFeedK4", request == null ? null : request.expectedFeedK4());
        String reason = request == null ? null : request.reason();
        try {
            return updateTamperAlertConfigInternal(idempotencyKey, request, operator, attempted);
        } catch (RuntimeException failure) {
            auditFailedJ3("J3_TAMPER_ALERT_CONFIG_FAILED", "TAMPER_ALERT_CONFIG", "default",
                    operator, idempotencyKey, reason, attempted, failure);
            throw failure;
        }
    }

    private ApiResult<Map<String, Object>> updateTamperAlertConfigInternal(
            String idempotencyKey,
            TamperAlertConfigRequest request,
            String operator,
            Object attempted) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            auditRejectedJ3("J3_TAMPER_ALERT_CONFIG_REJECTED", "TAMPER_ALERT_CONFIG", "default", operator,
                    guard.getMessage(), Map.of(), attempted, idempotencyKey,
                    request == null ? null : request.reason());
            return guard;
        }
        int threshold = request.threshold() == null ? 10 : request.threshold();
        if (threshold < 1 || threshold > 100) {
            auditRejectedJ3("J3_TAMPER_ALERT_CONFIG_REJECTED", "TAMPER_ALERT_CONFIG", "default", operator,
                    "TAMPER_THRESHOLD_RANGE_1_100", tamperAlertConfig(),
                    map("threshold", threshold, "feedK4", request.feedK4()),
                    idempotencyKey, request.reason());
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TAMPER_THRESHOLD_RANGE_1_100");
        }
        boolean feedK4 = request.feedK4() == null || request.feedK4();
        if (request.expectedThreshold() == null || request.expectedFeedK4() == null) {
            Map<String, Object> current = tamperAlertConfig();
            auditRejectedJ3("J3_TAMPER_ALERT_CONFIG_REJECTED", "TAMPER_ALERT_CONFIG", "default", operator,
                    "TAMPER_ALERT_CONFIG_EXPECTED_STATE_REQUIRED", current,
                    map("threshold", threshold, "feedK4", feedK4), idempotencyKey, request.reason());
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "TAMPER_ALERT_CONFIG_EXPECTED_STATE_REQUIRED");
        }
        String hash = requestHash(
                String.valueOf(threshold), String.valueOf(feedK4),
                String.valueOf(request.expectedThreshold()), String.valueOf(request.expectedFeedK4()),
                request.reason().trim());
        // Keep one lock order for every J3 config mutation: business row first,
        // idempotency row second. Different idempotency keys otherwise may take
        // insert-gap locks before contending for the shared config lock and
        // deadlock under MySQL REPEATABLE READ.
        Map<String, Object> lockedBefore = tamperAlertConfig(emergencyRepository.tamperConfigForUpdate());
        return idempotentJ3("J3_ALERT_CONFIG", idempotencyKey.trim(), hash, () -> {
            Map<String, Object> before = lockedBefore;
            int currentThreshold = intValue(before.get("threshold"));
            boolean currentFeedK4 = boolValue(before.get("feedK4"));
            if ((request.expectedThreshold() != null && request.expectedThreshold() != currentThreshold)
                    || (request.expectedFeedK4() != null && request.expectedFeedK4() != currentFeedK4)) {
                auditRejectedJ3("J3_TAMPER_ALERT_CONFIG_REJECTED", "TAMPER_ALERT_CONFIG", "default", operator,
                        "TAMPER_ALERT_CONFIG_CONFLICT", before, map("threshold", threshold, "feedK4", feedK4),
                        idempotencyKey, request.reason());
                return ApiResult.fail(409, "TAMPER_ALERT_CONFIG_CONFLICT");
            }
            Map<String, Object> after = map("threshold", threshold, "feedK4", feedK4);
            if (threshold == currentThreshold && feedK4 == currentFeedK4) {
                auditRejectedJ3("J3_TAMPER_ALERT_CONFIG_REJECTED", "TAMPER_ALERT_CONFIG", "default", operator,
                        "TAMPER_ALERT_CONFIG_UNCHANGED", before, after, idempotencyKey, request.reason());
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        "TAMPER_ALERT_CONFIG_UNCHANGED");
            }
            emergencyRepository.upsertSetting(TAMPER_THRESHOLD, String.valueOf(threshold), "NUMBER", GROUP_TAMPER, request.reason().trim(), operator);
            emergencyRepository.upsertSetting(TAMPER_FEED_K4, String.valueOf(feedK4), "BOOLEAN", GROUP_TAMPER, request.reason().trim(), operator);
            String adminAlertEventId = outboxService.publish(
                    "TAMPER_ALERT_CONFIG", "default", "ADMIN_J3_TAMPER_CONFIG_CHANGED", map(
                            "before", before,
                            "after", after,
                            "audienceRole", "SUPER_ADMIN",
                            "operator", operator,
                            "reason", request.reason().trim(),
                            "occurredAt", LocalDateTime.now().toString()));
            auditRequired("J3_TAMPER_ALERT_CONFIG_CHANGED", "TAMPER_ALERT_CONFIG", "default", operator, "HIGH", map(
                    "before", before,
                    "after", after,
                    "reason", request.reason().trim(),
                    "adminAlertEventId", adminAlertEventId,
                    "idempotencyKey", idempotencyKey.trim()));
            ApiResult<Map<String, Object>> result = tamperOverview("24h", 1, 5);
            if (result.getData() != null) {
                result.getData().put("adminAlertEventId", adminAlertEventId);
            }
            return result;
        }, "J3_TAMPER_ALERT_CONFIG_REJECTED", "TAMPER_ALERT_CONFIG", "default", operator,
                map("threshold", threshold, "feedK4", feedK4), request.reason());
    }

    @Transactional
    public ApiResult<Map<String, Object>> createTamperReport(String idempotencyKey, TamperReportRequest request) {
        String operator = authenticatedOperator(request == null ? null : request.operator());
        String requestedWindow = request == null ? null : request.window();
        String resourceId = StringUtils.hasText(requestedWindow) ? requestedWindow.trim() : "24h";
        Object attempted = map("window", requestedWindow);
        String reason = request == null ? null : request.reason();
        try {
            return createTamperReportInternal(idempotencyKey, request, operator, attempted);
        } catch (RuntimeException failure) {
            auditFailedJ3("J3_TAMPER_REPORT_FAILED", "TAMPER_REPORT", resourceId,
                    operator, idempotencyKey, reason, attempted, failure);
            throw failure;
        }
    }

    private ApiResult<Map<String, Object>> createTamperReportInternal(
            String idempotencyKey,
            TamperReportRequest request,
            String operator,
            Object attempted) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            auditRejectedJ3("J3_TAMPER_REPORT_REJECTED", "TAMPER_REPORT", "precondition", operator,
                    guard.getMessage(), Map.of(), attempted, idempotencyKey,
                    request == null ? null : request.reason());
            return guard;
        }
        String window = StringUtils.hasText(request.window()) ? request.window().trim() : "24h";
        TamperRange range = tamperRange(window, LocalDateTime.now());
        if (range == null) {
            auditRejectedJ3("J3_TAMPER_REPORT_REJECTED", "TAMPER_REPORT", "invalid-window", operator,
                    "TAMPER_WINDOW_INVALID", Map.of(), map("window", window),
                    idempotencyKey, request.reason());
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TAMPER_WINDOW_INVALID");
        }
        return idempotentJ3("J3_REPORT", idempotencyKey.trim(), requestHash(window, request.reason().trim()), () -> {
            String reportId = "J3-RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            int threshold = intValue(tamperAlertConfig().get("threshold"));
            List<Map<String, Object>> paths = tamperPaths(range);
            int eventCount = paths.stream().mapToInt(row -> intValue(row.get("count"))).sum();
            if (eventCount == 0) {
                auditRejectedJ3("J3_TAMPER_REPORT_REJECTED", "TAMPER_REPORT", "empty-window", operator,
                        "TAMPER_REPORT_EMPTY", map("window", window), map("window", window),
                        idempotencyKey, request.reason());
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "TAMPER_REPORT_EMPTY");
            }
            int effectiveThreshold = scaledTamperThreshold(threshold, range.label());
            long matchedAccountCount = emergencyRepository.countTamperAccounts(
                    range.startAt(), range.endAt(), effectiveThreshold);
            if (matchedAccountCount > TAMPER_REPORT_MAX_ACCOUNTS) {
                auditRejectedJ3("J3_TAMPER_REPORT_REJECTED", "TAMPER_REPORT", "too-large", operator,
                        "TAMPER_REPORT_TOO_LARGE", map("window", window, "accountCount", matchedAccountCount),
                        map("window", window, "maxAccounts", TAMPER_REPORT_MAX_ACCOUNTS),
                        idempotencyKey, request.reason());
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TAMPER_REPORT_TOO_LARGE");
            }
            List<Map<String, Object>> accounts = tamperAccounts(range, effectiveThreshold);
            String csv = tamperReportCsv(window, paths, accounts);
            String filename = "j3-tamper-" + window + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
            Map<String, Object> payload = map(
                    "paths", paths,
                    "accounts", accounts.stream().map(this::maskedTamperAccount).toList(),
                    "eventCount", eventCount,
                    "accountCount", accounts.size(),
                    "sources", List.of("nx_emergency_tamper_event"));
            emergencyRepository.createTamperReport(
                    reportId, window, true, "READY", payload, operator, request.reason().trim());
            auditRequired("J3_TAMPER_REPORT_EXPORTED", "TAMPER_REPORT", reportId, operator, "MEDIUM", map(
                    "reportId", reportId,
                    "window", window,
                    "eventCount", eventCount,
                    "accountCount", accounts.size(),
                    "source", "nx_emergency_tamper_report",
                    "masked", true,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(map(
                    "reportId", reportId,
                    "window", window,
                    "masked", true,
                    "status", "READY",
                    "filename", filename,
                    "contentType", "text/csv;charset=UTF-8",
                    "contentBase64", Base64.getEncoder().encodeToString(("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8)),
                    "eventCount", eventCount,
                    "accountCount", accounts.size(),
                    "source", "nx_emergency_tamper_report"));
        }, "J3_TAMPER_REPORT_REJECTED", "TAMPER_REPORT", window, operator,
                map("window", window), request.reason());
    }

    public ApiResult<Map<String, Object>> sopOverview() {
        List<Map<String, Object>> playbooks = new ArrayList<>(playbookSeeds().stream().map(this::playbookView).toList());
        List<Map<String, Object>> executions = executionSeeds().stream().map(source -> {
            Map<String, Object> row = new LinkedHashMap<>(source);
            row.put("reversible", isReversibleExecution(source));
            return row;
        }).toList();
        long ready = playbooks.stream().filter(row -> "active".equals(row.get("state"))).count();
        long emergency = playbooks.stream().filter(row -> Boolean.TRUE.equals(row.get("emergency"))).count();
        LocalDateTime since90d = LocalDateTime.now().minusDays(90);
        long drill90d = emergencyRepository.countExecutionsSinceByMode("drill", since90d);
        long liveExec90d = emergencyRepository.countExecutionsSinceByMode("regular", since90d)
                + emergencyRepository.countExecutionsSinceByMode("emergency", since90d);
        Map<String, Object> response = map(
                "domain", "J4",
                "contractVersion", "J4_REAL_EXECUTION_V3",
                "stats", map(
                        "playbookCount", playbooks.size(),
                        "readyCount", ready,
                        "todoCount", playbooks.size() - ready,
                        "emergencyCount", emergency,
                        "liveExec90d", liveExec90d,
                        "drill90d", drill90d),
                "scenes", List.of("全部", "监管点名", "资金异常", "数据泄露", "舆情挤兑", "技术故障"),
                "actionOptions", defaultActionOptions(),
                "rollbackOptions", defaultRollbackOptions(),
                "h1Rhythm", GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy).summary(),
                "playbooks", playbooks,
                "executions", executions,
                "sources", List.of("SOP 剧本库", "SOP 动作库", "SOP 执行记录", "通知活动", "审计记录", "增长节奏"));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createPlaybook(String idempotencyKey, SopPlaybookCreateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectJ4("PLAYBOOK_CREATE", "NEW", authenticatedOperator(request == null ? null : request.operator()),
                    request == null ? "" : request.reason(), idempotencyKey, guard);
        }
        String operator = authenticatedOperator(request.operator());
        return idempotentJ4(
                "J4_PLAYBOOK_CREATE",
                idempotencyKey.trim(),
                requestHash(String.valueOf(request), operator),
                () -> createPlaybookOnce(idempotencyKey, request, operator),
                "PLAYBOOK_CREATE", "NEW", operator, request.reason());
    }

    private ApiResult<Map<String, Object>> createPlaybookOnce(
            String idempotencyKey, SopPlaybookCreateRequest request, String operator) {
        emergencyRepository.ensureTables();
        emergencyRepository.lockPlaybookCatalogMutations();
        List<Map<String, Object>> rows = new ArrayList<>(emergencyRepository.playbooksIndependent());
        Map<String, Object> row = playbookDraftRow(nextDraftCode(rows), request);
        ApiResult<Map<String, Object>> definitionError = validatePlaybookDefinition(row, rows, null);
        if (definitionError != null) {
            return rejectJ4("PLAYBOOK_CREATE", stringValue(row.get("code"), ""), operator,
                    request.reason(), idempotencyKey, definitionError);
        }
        emergencyRepository.createPlaybook(
                stringValue(row.get("code"), ""),
                stringValue(row.get("name"), ""),
                stringValue(row.get("scene"), ""),
                boolValue(row.get("emergency")),
                stringValue(row.get("sla"), ""),
                stringValue(row.get("state"), "todo"),
                stringValue(row.get("owner"), ""),
                stringValue(row.get("notifyCampaignNo"), ""),
                stringValue(row.get("notifyTemplate"), ""),
                stringValue(row.get("rollback"), ""),
                boolValue(row.get("drillRequired")),
                boolValue(row.get("draft")),
                mapList(row.get("sequence")),
                operator);
        auditRequired("J4_SOP_PLAYBOOK_CREATED", "SOP_PLAYBOOK", String.valueOf(row.get("code")), operator, "HIGH", map(
                "code", row.get("code"),
                "name", row.get("name"),
                "scene", row.get("scene"),
                "notifyCampaignNo", row.get("notifyCampaignNo"),
                "notifyTemplate", row.get("notifyTemplate"),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = sopOverview().getData();
        response.put("updated", row);
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updatePlaybook(String code, String idempotencyKey, SopPlaybookUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectJ4("PLAYBOOK_UPDATE", code, authenticatedOperator(request == null ? null : request.operator()),
                    request == null ? "" : request.reason(), idempotencyKey, guard);
        }
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String operator = authenticatedOperator(request.operator());
        return idempotentJ4(
                "J4_PLAYBOOK_UPDATE:" + normalizedCode,
                idempotencyKey.trim(),
                requestHash(normalizedCode, String.valueOf(request), operator),
                () -> updatePlaybookOnce(normalizedCode, request, operator, idempotencyKey.trim()),
                "PLAYBOOK_UPDATE", normalizedCode, operator, request.reason());
    }

    private ApiResult<Map<String, Object>> updatePlaybookOnce(
            String code, SopPlaybookUpdateRequest request, String operator, String idempotencyKey) {
        emergencyRepository.ensureTables();
        emergencyRepository.lockPlaybookCatalogMutations();
        List<Map<String, Object>> rows = new ArrayList<>(emergencyRepository.playbooksIndependent());
        Optional<PlaybookSeed> foundSeed = rows.stream()
                .map(this::playbookFromRow)
                .filter(candidate -> candidate.code().equals(code))
                .findFirst();
        if (foundSeed.isEmpty()) {
            return rejectJ4("PLAYBOOK_UPDATE", code, operator, request.reason(), idempotencyKey,
                    ApiResult.fail(404, "J4_PLAYBOOK_NOT_FOUND"));
        }
        PlaybookSeed seed = foundSeed.get();
        if (!StringUtils.hasText(request.version())) {
            return rejectJ4("PLAYBOOK_UPDATE", seed.code(), operator, request.reason(), idempotencyKey,
                    ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_PLAYBOOK_VERSION_REQUIRED"));
        }
        Map<String, Object> candidate = previewPlaybookUpdate(seed, request);
        ApiResult<Map<String, Object>> definitionError = validatePlaybookDefinition(
                candidate, rows, seed.code());
        if (definitionError != null) {
            return rejectJ4("PLAYBOOK_UPDATE", seed.code(), operator, request.reason(), idempotencyKey, definitionError);
        }
        String summary = StringUtils.hasText(request.summary()) ? request.summary().trim() : seed.seq().size() + " steps";
        Map<String, Object> updatedRow = updatePlaybookRow(seed.code(), request, candidate, operator);
        if (updatedRow == null) {
            return rejectJ4("PLAYBOOK_UPDATE", seed.code(), operator, request.reason(), idempotencyKey,
                    ApiResult.fail(409, "J4_PLAYBOOK_VERSION_CONFLICT"));
        }
        auditRequired("J4_SOP_PLAYBOOK_EDITED", "SOP_PLAYBOOK", seed.code(), operator, "HIGH", map(
                "code", seed.code(),
                "summary", summary,
                "before", playbookRow(seed),
                "after", updatedRow,
                "notifyCampaignNo", updatedRow.get("notifyCampaignNo"),
                "notifyTemplate", updatedRow.get("notifyTemplate"),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return sopOverview();
    }

    @Transactional
    public ApiResult<Map<String, Object>> drillPlaybook(String code, String idempotencyKey, SopPlaybookRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectJ4("PLAYBOOK_DRILL", code, authenticatedOperator(request == null ? null : request.operator()),
                    request == null ? "" : request.reason(), idempotencyKey, guard);
        }
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String operator = authenticatedOperator(request.operator());
        return idempotentJ4(
                "J4_PLAYBOOK_DRILL:" + normalizedCode,
                idempotencyKey.trim(),
                requestHash(normalizedCode, String.valueOf(request), operator),
                () -> drillPlaybookOnce(normalizedCode, idempotencyKey.trim(), request, operator),
                "PLAYBOOK_DRILL", normalizedCode, operator, request.reason());
    }

    private ApiResult<Map<String, Object>> drillPlaybookOnce(
            String normalizedCode,
            String idempotencyKey,
            SopPlaybookRunRequest request,
            String operator) {
        Optional<Map<String, Object>> lockedPlaybook = emergencyRepository.playbookForUpdate(normalizedCode);
        Optional<Map<String, Object>> replay = emergencyRepository.executionByIdempotencyKeyIndependent(
                normalizedCode, idempotencyKey);
        if (replay.isPresent()) {
            if (!"drill".equals(replay.get().get("mode"))) {
                return rejectJ4("PLAYBOOK_DRILL", normalizedCode, operator, request.reason(), idempotencyKey,
                        ApiResult.fail(409, "J4_IDEMPOTENCY_OPERATION_CONFLICT"));
            }
            return sopOverview();
        }
        if (lockedPlaybook.isEmpty()) {
            return rejectJ4("PLAYBOOK_DRILL", normalizedCode, operator, request.reason(), idempotencyKey,
                    ApiResult.fail(404, "J4_PLAYBOOK_NOT_FOUND"));
        }
        PlaybookSeed seed = playbookFromRow(lockedPlaybook.get());
        ApiResult<Map<String, Object>> validation = validateExecutablePlaybook(seed, false);
        if (validation != null) {
            return rejectJ4("PLAYBOOK_DRILL", seed.code(), operator, request.reason(), idempotencyKey, validation);
        }
        LocalDateTime drillAt = LocalDateTime.now();
        String executionId = seed.code() + "-DRILL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Map<String, Object> campaign = inspectNotificationCampaign(seed);
        Map<String, Object> row = execution(
                drillAt.format(TS), seed.code(), seed.name() + " (演练)", request.reason().trim(), "drill",
                seed.seq().stream().map(ignored -> "done").toList(), operator, seed.owner());
        row.put("executionId", executionId);
        row.put("idempotencyKey", idempotencyKey);
        row.put("notificationDispatch", campaign);
        row.put("domainActions", seed.seq().stream().map(step -> map(
                "domain", step.get("domain"), "action", step.get("action"), "status", "VALIDATED")).toList());
        row.put("rollback", seed.rollback());
        emergencyRepository.createExecution(row);
        emergencyRepository.markPlaybookDrilled(seed.code(), drillAt, operator);
        auditRequired("J4_SOP_PLAYBOOK_DRILL_COMPLETED", "SOP_PLAYBOOK_EXECUTION", executionId, operator, "MEDIUM", map(
                "code", seed.code(),
                "validationOnly", true,
                "productionActionsExecuted", false,
                "stepResults", row.get("domainActions"),
                "notification", campaign,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey));
        return sopOverview();
    }

    @Transactional
    public ApiResult<Map<String, Object>> executePlaybook(String code, String idempotencyKey, SopPlaybookRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectJ4("PLAYBOOK_EXECUTE", code, authenticatedOperator(request == null ? null : request.operator()),
                    request == null ? "" : request.reason(), idempotencyKey, guard);
        }
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String operator = authenticatedOperator(request.operator());
        String executionRequestHash = requestHash(normalizedCode, String.valueOf(request), operator);
        Optional<Map<String, Object>> existingExecution = emergencyRepository.executionByIdempotencyKey(
                normalizedCode, idempotencyKey.trim());
        if (existingExecution.isPresent()) {
            Map<String, Object> existingNotification = mutableMap(existingExecution.get().get("notificationDispatch"));
            String originalRequestHash = stringValue(existingNotification.get("requestHash"), "");
            if (StringUtils.hasText(originalRequestHash) && !originalRequestHash.equals(executionRequestHash)) {
                return rejectJ4("PLAYBOOK_EXECUTE", normalizedCode, operator, request.reason(), idempotencyKey,
                        ApiResult.fail(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));
            }
            // Execution rows are the durable recovery authority. Bypass a previously cached
            // transient ApiResult so the same key can enter lease-based reconciliation.
            return executePlaybookOnce(
                    normalizedCode, idempotencyKey.trim(), executionRequestHash, request, operator);
        }
        return idempotentJ4(
                "J4_PLAYBOOK_EXECUTE:" + normalizedCode,
                idempotencyKey.trim(),
                executionRequestHash,
                () -> executePlaybookOnce(
                        normalizedCode, idempotencyKey.trim(), executionRequestHash, request, operator),
                "PLAYBOOK_EXECUTE", normalizedCode, operator, request.reason());
    }

    private ApiResult<Map<String, Object>> executePlaybookOnce(
            String normalizedCode,
            String idempotencyKey,
            String executionRequestHash,
            SopPlaybookRunRequest request,
            String operator) {
        Optional<Map<String, Object>> lockedPlaybook = emergencyRepository.playbookForUpdate(normalizedCode);
        SopPlaybookRunRequest effectiveRequest = new SopPlaybookRunRequest(
                request.emergency(), request.reason(), operator,
                request.triggerBasis(), request.triggerContext(), request.stepConfirmations());
        boolean emergency = request.emergency() != null && request.emergency();
        Optional<Map<String, Object>> existingExecution = emergencyRepository.executionByIdempotencyKeyIndependent(
                normalizedCode, idempotencyKey);
        if (existingExecution.isPresent()) {
            String snapshotCode = stringValue(existingExecution.get().get("code"), normalizedCode)
                    .toUpperCase(Locale.ROOT);
            if ("drill".equals(existingExecution.get().get("mode"))) {
                return rejectJ4("PLAYBOOK_EXECUTE", snapshotCode, operator, request.reason(), idempotencyKey,
                        ApiResult.fail(409, "J4_IDEMPOTENCY_OPERATION_CONFLICT"));
            }
            Map<String, Object> existingNotification = mutableMap(
                    existingExecution.get().get("notificationDispatch"));
            String storedRequestHash = stringValue(existingNotification.get("requestHash"), "");
            boolean legacySnapshotMismatch = !StringUtils.hasText(storedRequestHash)
                    && (!stringValue(existingExecution.get().get("trigger"), "").equals(request.reason().trim())
                    || !sameAdminActor(existingExecution.get().get("operator"), operator)
                    || !stringValue(existingExecution.get().get("mode"), "regular").equals(
                            emergency ? "emergency" : "regular"));
            if ((StringUtils.hasText(storedRequestHash) && !storedRequestHash.equals(executionRequestHash))
                    || legacySnapshotMismatch) {
                return rejectJ4("PLAYBOOK_EXECUTE", snapshotCode, operator, request.reason(), idempotencyKey,
                        ApiResult.fail(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));
            }
            String existingExecId = stringValue(existingExecution.get().get("executionId"), "");
            List<String> existingSteps = stringList(existingExecution.get().get("steps"));
            if (existingSteps.contains("pending") || existingSteps.contains("running")) {
                if (!emergencyRepository.claimExecutionRecovery(
                        existingExecId, LocalDateTime.now().minus(J4_EXECUTION_RECOVERY_LEASE))) {
                    return ApiResult.fail(409, "J4_EXECUTION_IN_PROGRESS:" + existingExecId);
                }
                return reconcileStaleExecution(
                        existingExecution.get(), snapshotCode, operator, idempotencyKey);
            }
            if (existingSteps.contains("failed")) {
                ensureExecutionOutcomeAudit(
                        existingExecution.get(), snapshotCode, operator, idempotencyKey, true);
                return ApiResult.fail(409, "J4_EXECUTION_PARTIAL:" + existingExecId);
            }
            if (existingSteps.stream().anyMatch(status -> !Set.of("done", "skipped", "rolled_back").contains(status))) {
                return rejectJ4("PLAYBOOK_EXECUTE", snapshotCode, operator, request.reason(), idempotencyKey,
                        ApiResult.fail(409, "J4_EXECUTION_STATUS_INVALID:" + existingExecId));
            }
            ensureExecutionOutcomeAudit(
                    existingExecution.get(), snapshotCode, operator, idempotencyKey, false);
            Map<String, Object> response = sopOverview().getData();
            response.put("updated", map(
                    "executionId", existingExecId,
                    "code", snapshotCode,
                    "idempotentReplay", true));
            return ApiResult.ok(response);
        }
        if (lockedPlaybook.isEmpty()) {
            return rejectJ4("PLAYBOOK_EXECUTE", normalizedCode, operator, request.reason(), idempotencyKey,
                    ApiResult.fail(404, "J4_PLAYBOOK_NOT_FOUND"));
        }
        PlaybookSeed seed = playbookFromRow(lockedPlaybook.get());
        if (emergency && !seed.emergency()) {
            return rejectJ4("PLAYBOOK_EXECUTE", seed.code(), operator, request.reason(), idempotencyKey,
                    ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PLAYBOOK_NOT_EMERGENCY_TRACK"));
        }
        ApiResult<Map<String, Object>> authorityError = requireTargetAuthorities(seed);
        if (authorityError != null) {
            return rejectJ4("PLAYBOOK_EXECUTE", seed.code(), operator, request.reason(), idempotencyKey, authorityError);
        }
        ApiResult<Map<String, Object>> validation = validateExecutablePlaybook(seed, true);
        if (validation != null) {
            return rejectJ4("PLAYBOOK_EXECUTE", seed.code(), operator, request.reason(), idempotencyKey, validation);
        }
        ApiResult<Map<String, Object>> confirmationError = validateJ4StepConfirmations(seed, request);
        if (confirmationError != null) {
            return rejectJ4("PLAYBOOK_EXECUTE", seed.code(), operator, request.reason(), idempotencyKey,
                    confirmationError);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("J", "playbook", seed.code()) > 0) {
            return rejectJ4("PLAYBOOK_EXECUTE", seed.code(), operator, request.reason(), idempotencyKey,
                    ApiResult.fail(409, "OBJECT_LOCKED_BY_A2"));
        }
        String execId = seed.code() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        List<Map<String, Object>> domainActions = pendingDomainActions(seed, execId, operator);
        List<String> stepStatuses = new ArrayList<>();
        for (int index = 0; index < seed.seq().size(); index++) {
            stepStatuses.add("pending");
        }
        Map<String, Object> notificationDispatch = map(
                "required", false,
                "status", "SKIPPED",
                "auditStatus", "PENDING",
                "requestHash", executionRequestHash,
                "triggerBasis", request.triggerBasis().trim(),
                "triggerContext", request.triggerContext().trim(),
                "stepConfirmations", request.stepConfirmations());
        String failure = null;
        appendExecution(seed, execId, emergency, idempotencyKey, effectiveRequest,
                notificationDispatch, domainActions, stepStatuses);
        try {
            auditRequiredInNewTransaction("J4_SOP_PLAYBOOK_EXECUTION_STARTED", "SOP_PLAYBOOK_EXECUTION",
                    execId, operator, emergency ? "CRITICAL" : "HIGH", map(
                             "code", seed.code(),
                             "emergency", emergency,
                             "steps", stepStatuses,
                             "triggerBasis", request.triggerBasis().trim(),
                             "triggerContext", request.triggerContext().trim(),
                             "stepConfirmations", request.stepConfirmations(),
                             "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey));
        } catch (RuntimeException auditFailure) {
            stepStatuses.set(0, "failed");
            markDomainActionStatus(domainActions, 1, "FAILED");
            for (int remaining = 1; remaining < stepStatuses.size(); remaining++) {
                stepStatuses.set(remaining, "skipped");
                markDomainActionStatus(domainActions, remaining + 1, "SKIPPED");
            }
            notificationDispatch.put("failure", "J4_EXECUTION_START_AUDIT_FAILED");
            notificationDispatch.put("auditStatus", "PENDING");
            notificationDispatch.put("requestHash", executionRequestHash);
            emergencyRepository.updateExecutionProgressIndependent(
                    execId, stepStatuses, notificationDispatch, domainActions);
            throw auditFailure;
        }
        for (int index = 0; index < seed.seq().size(); index++) {
            Map<String, Object> step = seed.seq().get(index);
            String domain = stringValue(step.get("domain"), "").toUpperCase(Locale.ROOT);
            String action = stringValue(step.get("action"), "").replace("**", "").trim();
            boolean sideEffectCompleted = false;
            try {
                stepStatuses.set(index, "running");
                markDomainActionStatus(domainActions, index + 1, "RUNNING");
                emergencyRepository.updateExecutionProgressIndependent(
                        execId, stepStatuses, notificationDispatch, domainActions);
                if ("J1".equals(domain)) {
                    executeJ1Action(domainActions, index + 1, action, action.toLowerCase(Locale.ROOT), execId, effectiveRequest);
                } else if ("I3".equals(domain)) {
                    Map<String, Object> dispatchedNotification = notificationDispatch(seed, execId, effectiveRequest);
                    if (dispatchedNotification == null) {
                        throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_NOTIFY_CAMPAIGN_NOT_READY");
                    }
                    dispatchedNotification.put("auditStatus", "PENDING");
                    dispatchedNotification.put("requestHash", executionRequestHash);
                    dispatchedNotification.put("triggerBasis", request.triggerBasis().trim());
                    dispatchedNotification.put("triggerContext", request.triggerContext().trim());
                    dispatchedNotification.put("stepConfirmations", request.stepConfirmations());
                    notificationDispatch = dispatchedNotification;
                }
                sideEffectCompleted = true;
                stepStatuses.set(index, "done");
                markDomainActionStatus(domainActions, index + 1, "DONE");
                emergencyRepository.updateExecutionProgressIndependent(
                        execId, stepStatuses, notificationDispatch, domainActions);
            } catch (RuntimeException ex) {
                stepStatuses.set(index, sideEffectCompleted ? "running" : "failed");
                for (int remaining = index + 1; remaining < stepStatuses.size(); remaining++) {
                    stepStatuses.set(remaining, "skipped");
                    markDomainActionStatus(domainActions, remaining + 1, "SKIPPED");
                }
                markDomainActionStatus(domainActions, index + 1, sideEffectCompleted ? "RUNNING" : "FAILED");
                failure = sideEffectCompleted ? "J4_EXECUTION_PROGRESS_WRITE_FAILED" : safeJ4StepFailure(ex);
                notificationDispatch = notificationDispatch == null
                        ? map(
                                "required", true,
                                "status", "FAILED",
                                "auditStatus", "PENDING",
                                "requestHash", executionRequestHash,
                                "triggerBasis", request.triggerBasis().trim(),
                                "triggerContext", request.triggerContext().trim(),
                                "stepConfirmations", request.stepConfirmations())
                        : notificationDispatch;
                notificationDispatch.put("failure", failure);
                if (!sideEffectCompleted) {
                    emergencyRepository.updateExecutionProgressIndependent(
                            execId, stepStatuses, notificationDispatch, domainActions);
                }
                break;
            }
        }
        if (failure != null) {
            auditRequiredWithResult("J4_SOP_PLAYBOOK_PARTIAL", "SOP_PLAYBOOK_EXECUTION", execId, operator,
                    "CRITICAL", "FAILED", map(
                    "code", seed.code(),
                     "steps", stepStatuses,
                     "failure", failure,
                     "triggerBasis", request.triggerBasis().trim(),
                     "triggerContext", request.triggerContext().trim(),
                     "stepConfirmations", request.stepConfirmations(),
                     "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey));
            notificationDispatch.put("auditStatus", "AUDITED");
            emergencyRepository.updateExecutionProgress(
                    execId, stepStatuses, notificationDispatch, domainActions);
            return ApiResult.fail(409, "J4_EXECUTION_PARTIAL:" + execId + ":" + failure);
        }
        auditRequired("J4_SOP_PLAYBOOK_EXECUTED", "SOP_PLAYBOOK_EXECUTION", execId, operator, emergency ? "CRITICAL" : "HIGH", map(
                "code", seed.code(),
                "emergency", emergency,
                "steps", seed.seq(),
                "domainActions", domainActions,
                     "notificationDispatch", notificationDispatch,
                     "rollback", seed.rollback(),
                     "triggerBasis", request.triggerBasis().trim(),
                     "triggerContext", request.triggerContext().trim(),
                     "stepConfirmations", request.stepConfirmations(),
                     "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey));
        notificationDispatch.put("auditStatus", "AUDITED");
        emergencyRepository.updateExecutionProgress(
                execId, stepStatuses, notificationDispatch, domainActions);
        Map<String, Object> response = sopOverview().getData();
        response.put("updated", map(
                "executionId", execId,
                "code", seed.code(),
                "emergency", emergency,
                "domainActions", domainActions,
                "notificationDispatch", notificationDispatch,
                "rollback", seed.rollback()));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> rollbackPlaybookExecution(
            String code,
            String executionId,
            String idempotencyKey,
            SopPlaybookRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectJ4("PLAYBOOK_ROLLBACK", executionId,
                    authenticatedOperator(request == null ? null : request.operator()),
                    request == null ? "" : request.reason(), idempotencyKey, guard);
        }
        String requestedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String execId = stringValue(executionId, "").trim();
        String actor = authenticatedOperator(request.operator());
        if (!hasAuthenticatedAuthority("emergency_j1_gate_resume")) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(403, "J4_TARGET_AUTHORITY_REQUIRED:J1:emergency_j1_gate_resume"));
        }
        if (!StringUtils.hasText(execId)) {
            return rejectJ4("PLAYBOOK_ROLLBACK", requestedCode, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_EXECUTION_ID_REQUIRED"));
        }
        String normalizedIdempotencyKey = idempotencyKey.trim();
        String rollbackRequestHash = requestHash(
                requestedCode,
                execId,
                actor,
                request.reason().trim(),
                String.valueOf(request.emergency()));
        Optional<Map<String, Object>> durableExecution = emergencyRepository.executionIndependent(execId);
        if (durableExecution.isPresent()
                && "ROLLED_BACK".equals(stringValue(durableExecution.get().get("rollbackStatus"), ""))) {
            if (matchesDurableRollbackRequest(
                    durableExecution.get(), requestedCode, actor, request.reason(),
                    normalizedIdempotencyKey, rollbackRequestHash)) {
                return durableRollbackReplay(durableExecution.get(), requestedCode, execId);
            }
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), normalizedIdempotencyKey,
                    ApiResult.fail(409, "J4_ROLLBACK_REPLAY_MISMATCH"));
        }
        return idempotentJ4(
                "J4_PLAYBOOK_ROLLBACK:" + execId,
                normalizedIdempotencyKey,
                rollbackRequestHash,
                () -> rollbackPlaybookExecutionOnce(
                        requestedCode, execId, normalizedIdempotencyKey, rollbackRequestHash, request, actor),
                "PLAYBOOK_ROLLBACK", execId, actor, request.reason());
    }

    private ApiResult<Map<String, Object>> rollbackPlaybookExecutionOnce(
            String requestedCode,
            String execId,
            String idempotencyKey,
            String rollbackRequestHash,
            SopPlaybookRunRequest request,
            String actor) {
        Optional<Map<String, Object>> found = emergencyRepository.execution(execId)
                .filter(row -> !StringUtils.hasText(requestedCode)
                        || requestedCode.equals(stringValue(row.get("code"), "").toUpperCase(Locale.ROOT)));
        if (found.isEmpty()) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_EXECUTION_NOT_FOUND"));
        }
        Map<String, Object> execution = found.get();
        String effectiveCode = StringUtils.hasText(requestedCode)
                ? requestedCode
                : stringValue(execution.get("code"), "").toUpperCase(Locale.ROOT);
        boolean playbookSnapshotMissing = findPlaybookSeed(effectiveCode).isEmpty();
        if ("ROLLED_BACK".equals(stringValue(execution.get("rollbackStatus"), ""))) {
            if (matchesDurableRollbackRequest(
                    execution, requestedCode, actor, request.reason(), idempotencyKey, rollbackRequestHash)) {
                return durableRollbackReplay(execution, requestedCode, execId);
            }
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(409, "J4_ROLLBACK_REPLAY_MISMATCH"));
        }
        List<String> executionSteps = stringList(execution.get("steps"));
        if (executionSteps.contains("pending") || executionSteps.contains("running")) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(409, "J4_EXECUTION_IN_PROGRESS:" + execId));
        }
        Map<String, Object> executionNotification = mutableMap(execution.get("notificationDispatch"));
        if (!"AUDITED".equals(stringValue(executionNotification.get("auditStatus"), ""))) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(409, "J4_EXECUTION_AUDIT_PENDING:" + execId));
        }
        List<Map<String, Object>> domainActions = mapList(execution.get("domainActions"));
        List<Map<String, Object>> reversibleActions = reversibleJ1Actions(domainActions);
        if (reversibleActions.isEmpty()) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_EXECUTION_NOT_REVERSIBLE"));
        }
        if (!hasAuthenticatedAuthority("emergency_j1_gate_resume")) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(403, "J4_TARGET_AUTHORITY_REQUIRED:J1:emergency_j1_gate_resume"));
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("J", "playbook_execution", execId) > 0) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(409, "OBJECT_LOCKED_BY_A2"));
        }
        List<Map<String, Object>> rollbackWrites;
        try {
            rollbackWrites = new TransactionTemplate(transactionManager).execute(status -> {
                if (!emergencyRepository.claimExecutionRollback(execId)) {
                    throw new J4RollbackRejectedException(
                            ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_ROLLBACK_IN_PROGRESS"),
                            true);
                }
                for (Map<String, Object> action : reversibleActions) {
                    String configKey = stringValue(action.get("configKey"), "");
                    String gateKey = configKey.substring("killswitch.".length());
                    ApiResult<Map<String, Object>> recovery = killSwitchService.restoreFromJ4Execution(
                            gateKey,
                            actor,
                            request.reason(),
                            execId,
                            stringValue(action.get("ownershipToken"), ""));
                    if (recovery.getCode() != 0) {
                        throw new J4RollbackRejectedException(recovery, false);
                    }
                }
                List<Map<String, Object>> writes = rollbackDomainActions(
                        execId, reversibleActions, actor, idempotencyKey, rollbackRequestHash);
                if (!emergencyRepository.completeExecutionRollback(
                        execId, LocalDateTime.now(), request.reason().trim(), writes)) {
                    throw new J4RollbackRejectedException(
                            ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_ROLLBACK_STATE_CONFLICT"),
                            false);
                }
                auditRequired("J4_SOP_PLAYBOOK_ROLLED_BACK", "SOP_PLAYBOOK_EXECUTION", execId, actor, "HIGH", map(
                        "code", effectiveCode,
                        "playbookSnapshotMissing", playbookSnapshotMissing,
                        "domainActions", writes,
                        "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey));
                return writes;
            });
        } catch (J4RollbackRejectedException rejected) {
            if (rejected.claimConflict()) {
                Optional<Map<String, Object>> latest = emergencyRepository.executionIndependent(execId);
                if (latest.isPresent()
                        && "ROLLED_BACK".equals(stringValue(latest.get().get("rollbackStatus"), ""))) {
                    if (matchesDurableRollbackRequest(
                            latest.get(), requestedCode, actor, request.reason(),
                            idempotencyKey, rollbackRequestHash)) {
                        return durableRollbackReplay(latest.get(), requestedCode, execId);
                    }
                    return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                            ApiResult.fail(409, "J4_ROLLBACK_REPLAY_MISMATCH"));
                }
            }
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    rejected.rejection());
        } catch (BizException rejected) {
            return rejectJ4("PLAYBOOK_ROLLBACK", execId, actor, request.reason(), idempotencyKey,
                    ApiResult.fail(rejected.getCode(), rejected.getMessage()));
        }
        Map<String, Object> response = sopOverview().getData();
        response.put("updated", map(
                "executionId", execId,
                "code", effectiveCode,
                "playbookSnapshotMissing", playbookSnapshotMissing,
                "rollbackStatus", "ROLLED_BACK",
                "domainActions", rollbackWrites));
        return ApiResult.ok(response);
    }

    private Map<String, Object> notificationDispatch(PlaybookSeed seed, String execId, SopPlaybookRunRequest request) {
        if (!StringUtils.hasText(seed.notifyCampaignNo())) {
            return map("required", false, "status", "SKIPPED");
        }
        Optional<NotificationEmergencyDispatchResult> result = notificationDispatchFacade.dispatchEmergencyCampaign(
                seed.notifyCampaignNo(),
                seed.code(),
                execId,
                request.operator(),
                request.reason());
        return result.map(value -> map(
                "required", true,
                "status", "DISPATCHED",
                "campaignNo", value.campaignNo(),
                "name", value.name(),
                "tier", value.tier(),
                "audience", value.audience(),
                "campaignStatus", value.status(),
                "notificationCount", value.notificationCount()))
                .orElse(null);
    }

    private List<Map<String, Object>> executeDomainActions(PlaybookSeed seed, String execId, SopPlaybookRunRequest request) {
        List<Map<String, Object>> writes = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> step : seed.seq()) {
            index++;
            String domain = stringValue(step.get("domain"), "").toUpperCase(Locale.ROOT);
            String action = stringValue(step.get("action"), "").replace("**", "");
            String normalizedAction = action.toLowerCase(Locale.ROOT);
            switch (domain) {
                case "J1" -> executeJ1Action(writes, index, action, normalizedAction, execId, request);
                case "D2" -> executeD2Action(writes, index, action, normalizedAction, execId, request);
                case "B1" -> executeB1Action(writes, index, action, execId, request);
                case "C2" -> executeC2Action(writes, index, action, normalizedAction, execId, request);
                case "K1" -> addDomainWrite(writes, "K1", index, action, "risk.k1.cluster_investigation.required",
                        "true", "BOOLEAN", "risk", "J4 K1 cluster investigation escalation | execId=" + execId, request);
                case "J2" -> addDomainWrite(writes, "J2", index, action, GEO_J4_BLOCK_REQUIRED,
                        "true", "BOOLEAN", GROUP_GEO_BLOCK, "J4 J2 geo block escalation | execId=" + execId, request);
                case "I4", "I5" -> executeDisclosureAction(writes, index, domain, action, execId, request);
                default -> {
                    // I3 notification dispatch is handled once per playbook, not as a config write.
                }
            }
        }
        return writes;
    }

    private void executeJ1Action(List<Map<String, Object>> writes, int stepIndex, String action,
                                 String normalizedAction, String execId, SopPlaybookRunRequest request) {
        if (SOP_J1_WITHDRAW_ACTION.equals(action)) {
            addDomainWrite(writes, "J1", stepIndex, action, "killswitch.withdraw", "disabled",
                    "STRING", "admin_killswitch", "J4 withdraw kill switch | execId=" + execId, request);
        }
        if (SOP_J1_GENESIS_ACTION.equals(action)) {
            addDomainWrite(writes, "J1", stepIndex, action, "killswitch.genesis", "disabled",
                    "STRING", "admin_killswitch", "J4 Genesis kill switch | execId=" + execId, request);
        }
    }

    private void executeD2Action(List<Map<String, Object>> writes, int stepIndex, String action,
                                 String normalizedAction, String execId, SopPlaybookRunRequest request) {
        if (containsAny(normalizedAction, "rate", "limit", "限流", "降速", "50")) {
            addDomainWrite(writes, "D2", stepIndex, action, "withdrawal.j4.rate_limit_pct", "50",
                    "NUMBER", "wallet", "J4 D2 withdrawal rate limit | execId=" + execId, request);
        }
        if (containsAny(normalizedAction, "batch", "分批", "放行", "b1")) {
            addDomainWrite(writes, "D2", stepIndex, action, "withdrawal.j4.batch_release.mode", "B1_CAPACITY",
                    "STRING", "wallet", "J4 D2 B1-capacity batch release | execId=" + execId, request);
        }
    }

    private void executeB1Action(List<Map<String, Object>> writes, int stepIndex, String action,
                                 String execId, SopPlaybookRunRequest request) {
        addDomainWrite(writes, "B1", stepIndex, action, "treasury.j4.coverage_check.required", "true",
                "BOOLEAN", "wallet", "J4 B1 coverage check required | execId=" + execId, request);
        addDomainWrite(writes, "B1", stepIndex, action, "treasury.j4.coverage_check.execution", execId,
                "STRING", "wallet", "J4 B1 coverage execution link", request);
    }

    private void executeC2Action(List<Map<String, Object>> writes, int stepIndex, String action,
                                 String normalizedAction, String execId, SopPlaybookRunRequest request) {
        if (containsAny(normalizedAction, "readonly", "只读")) {
            addDomainWrite(writes, "C2", stepIndex, action, "user.j4.account_readonly.required", "true",
                    "BOOLEAN", "user", "J4 C2 account readonly action | execId=" + execId, request);
            return;
        }
        addDomainWrite(writes, "C2", stepIndex, action, "user.j4.account_freeze.required", "true",
                "BOOLEAN", "user", "J4 C2 account freeze action | execId=" + execId, request);
    }

    private void executeDisclosureAction(List<Map<String, Object>> writes, int stepIndex, String sourceDomain,
                                         String action, String execId, SopPlaybookRunRequest request) {
        for (String gate : List.of("withdraw", "exchange", "staking", "genesis")) {
            addDomainWrite(writes, sourceDomain, stepIndex, action, "disclosure.gate." + gate, "true",
                    "BOOLEAN", "content", "J4 I5 disclosure gate required | execId=" + execId, request);
        }
        addDomainWrite(writes, sourceDomain, stepIndex, action, "disclosure.gate.lastExecution", execId,
                "STRING", "content", "J4 I5 disclosure gate execution link", request);
    }

    @SuppressWarnings("unchecked")
    private void addDomainWrite(List<Map<String, Object>> writes, String domain, int stepIndex, String action,
                                 String configKey, String value, String valueType, String group, String remark,
                                 SopPlaybookRunRequest request) {
        if (J1_CANONICAL_GATE_SETTINGS.contains(configKey)) {
            String gateKey = configKey.substring("killswitch.".length());
            boolean enable = "enabled".equalsIgnoreCase(value);
            ApiResult<Map<String, Object>> gateChange = killSwitchService.changeFromJ4Execution(
                    gateKey,
                    request.operator(),
                    request.reason(),
                    executionIdFromRemark(remark));
            if (gateChange.getCode() != 0) {
                throw new BizException(gateChange.getCode(), gateChange.getMessage());
            }
            Object updatedValue = gateChange.getData() == null ? null : gateChange.getData().get("updated");
            Map<String, Object> updated = updatedValue instanceof Map<?, ?>
                    ? (Map<String, Object>) updatedValue
                    : Map.of();
            putDomainWrite(writes, map(
                    "domain", domain,
                    "step", stepIndex,
                    "action", action,
                    "configKey", configKey,
                    "beforeValue", Boolean.TRUE.equals(updated.get("before")) ? "enabled" : "disabled",
                    "value", value,
                    "valueType", valueType,
                    "group", group,
                    "ownershipToken", stringValue(updated.get("ownershipToken"), ""),
                    "status", "DONE",
                    "operator", stringValue(request.operator(), "system")));
            return;
        } else if (appliesToEmergencyControlSetting(configKey)) {
            emergencyRepository.upsertSetting(configKey, value, valueType, group, remark, stringValue(request.operator(), "system"));
        }
        putDomainWrite(writes, map(
                "domain", domain,
                "step", stepIndex,
                "action", action,
                "configKey", configKey,
                "value", value,
                "valueType", valueType,
                "group", group,
                "status", "DONE",
                "operator", stringValue(request.operator(), "system")));
    }

    private void putDomainWrite(List<Map<String, Object>> writes, Map<String, Object> next) {
        int step = intValue(next.get("step"));
        String configKey = stringValue(next.get("configKey"), "");
        for (int index = 0; index < writes.size(); index++) {
            Map<String, Object> existing = writes.get(index);
            if (intValue(existing.get("step")) == step
                    && configKey.equals(stringValue(existing.get("configKey"), ""))) {
                writes.set(index, next);
                return;
            }
        }
        writes.add(next);
    }

    private String executionIdFromRemark(String remark) {
        int marker = remark == null ? -1 : remark.lastIndexOf("execId=");
        if (marker < 0) {
            throw new IllegalStateException("J4_EXECUTION_ID_MISSING");
        }
        String executionId = remark.substring(marker + "execId=".length()).trim();
        if (!StringUtils.hasText(executionId)) {
            throw new IllegalStateException("J4_EXECUTION_ID_MISSING");
        }
        return executionId;
    }

    private boolean appliesToEmergencyControlSetting(String configKey) {
        return StringUtils.hasText(configKey)
                && (configKey.startsWith("killswitch.") || configKey.startsWith("emergency.killswitch."));
    }

    private List<Map<String, Object>> rollbackDomainActions(
            String execId,
            List<Map<String, Object>> domainActions,
            String actor,
            String idempotencyKey,
            String rollbackRequestHash) {
        List<Map<String, Object>> writes = new ArrayList<>();
        for (Map<String, Object> action : domainActions) {
            String configKey = stringValue(action.get("configKey"), "");
            Optional<String> restoreValue = rollbackValueFor(configKey, execId);
            if (restoreValue.isEmpty()) {
                continue;
            }
            String valueType = stringValue(action.get("valueType"), "STRING");
            String group = stringValue(action.get("group"), GROUP_SOP);
            boolean restoredThroughJ1 = Set.of(
                    "killswitch.withdraw", "killswitch.exchange", "killswitch.genesis").contains(configKey)
                    && "enabled".equalsIgnoreCase(restoreValue.get());
            if (appliesToEmergencyControlSetting(configKey) && !restoredThroughJ1) {
                emergencyRepository.upsertSetting(
                        configKey,
                        restoreValue.get(),
                        valueType,
                        group,
                        "J4 rollback | execId=" + execId,
                        actor);
            }
            writes.add(map(
                    "domain", stringValue(action.get("domain"), ""),
                    "step", action.get("step"),
                    "configKey", configKey,
                    "value", restoreValue.get(),
                    "valueType", valueType,
                    "group", group,
                    "operator", actor,
                    "rollbackIdempotencyKey", idempotencyKey,
                    "rollbackRequestHash", rollbackRequestHash));
        }
        return writes;
    }

    private boolean matchesDurableRollbackRequest(
            Map<String, Object> execution,
            String requestedCode,
            String actor,
            String reason,
            String idempotencyKey,
            String rollbackRequestHash) {
        String durableCode = stringValue(execution.get("code"), "").toUpperCase(Locale.ROOT);
        if (StringUtils.hasText(requestedCode) && !requestedCode.equals(durableCode)) {
            return false;
        }
        if (!stringValue(execution.get("rollbackReason"), "").equals(stringValue(reason, "").trim())) {
            return false;
        }
        List<Map<String, Object>> rollbackActions = mapList(execution.get("rollbackActions"));
        if (rollbackActions.isEmpty()) {
            return false;
        }
        Map<String, Object> durableMarker = rollbackActions.get(0);
        if (!sameAdminActor(durableMarker.get("operator"), actor)
                || !stringValue(durableMarker.get("rollbackIdempotencyKey"), "").equals(idempotencyKey)) {
            return false;
        }
        String durableRequestHash = stringValue(durableMarker.get("rollbackRequestHash"), "");
        return !StringUtils.hasText(durableRequestHash)
                || durableRequestHash.equals(rollbackRequestHash);
    }

    private ApiResult<Map<String, Object>> durableRollbackReplay(
            Map<String, Object> execution,
            String requestedCode,
            String execId) {
        String effectiveCode = StringUtils.hasText(requestedCode)
                ? requestedCode
                : stringValue(execution.get("code"), "").toUpperCase(Locale.ROOT);
        Map<String, Object> response = sopOverview().getData();
        response.put("updated", map(
                "executionId", execId,
                "code", effectiveCode,
                "idempotentReplay", true,
                "rollbackStatus", "ROLLED_BACK"));
        return ApiResult.ok(response);
    }

    private List<Map<String, Object>> reversibleJ1Actions(List<Map<String, Object>> domainActions) {
        Set<String> reversibleKeys = Set.of(
                "killswitch.withdraw", "killswitch.exchange", "killswitch.genesis");
        return domainActions.stream()
                .filter(action -> "J1".equals(
                        stringValue(action.get("domain"), "").toUpperCase(Locale.ROOT)))
                .filter(action -> reversibleKeys.contains(stringValue(action.get("configKey"), "")))
                .filter(action -> "DONE".equals(
                        stringValue(action.get("status"), "").toUpperCase(Locale.ROOT)))
                .filter(action -> StringUtils.hasText(stringValue(action.get("ownershipToken"), "")))
                .toList();
    }

    private Optional<String> rollbackValueFor(String configKey, String execId) {
        if (!StringUtils.hasText(configKey)) {
            return Optional.empty();
        }
        if (Set.of("killswitch.withdraw", "killswitch.exchange", "killswitch.genesis").contains(configKey)) {
            return Optional.of("enabled");
        }
        if ("killswitch.maintenance".equals(configKey)) {
            return Optional.of("disabled");
        }
        if ("withdrawal.j4.rate_limit_pct".equals(configKey)) {
            return Optional.of("100");
        }
        if ("withdrawal.j4.batch_release.mode".equals(configKey)) {
            return Optional.of("OFF");
        }
        if ("treasury.j4.coverage_check.execution".equals(configKey)) {
            return Optional.of("rolled_back:" + execId);
        }
        if (configKey.startsWith("disclosure.gate.")
                || configKey.endsWith(".required")
                || configKey.endsWith(".pending_b1")) {
            return Optional.of("false");
        }
        return Optional.empty();
    }

    private boolean containsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && value.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void appendExecution(
            PlaybookSeed seed,
            String execId,
            boolean emergency,
            String idempotencyKey,
            SopPlaybookRunRequest request,
            Map<String, Object> notificationDispatch,
            List<Map<String, Object>> domainActions,
            List<String> stepStatuses) {
        Map<String, Object> row = execution(
                LocalDateTime.now().format(TS),
                seed.code(),
                seed.name(),
                request.reason().trim(),
                emergency ? "emergency" : "regular",
                stepStatuses,
                stringValue(request.operator(), "system"),
                seed.owner());
        row.put("executionId", execId);
        row.put("notificationDispatch", notificationDispatch);
        row.put("domainActions", domainActions);
        row.put("rollback", seed.rollback());
        row.put("idempotencyKey", idempotencyKey);
        emergencyRepository.createExecutionIndependent(row);
    }

    private void ensureExecutionOutcomeAudit(
            Map<String, Object> execution,
            String playbookCode,
            String operator,
            String idempotencyKey,
            boolean partial) {
        Map<String, Object> notification = mutableMap(execution.get("notificationDispatch"));
        if ("AUDITED".equals(stringValue(notification.get("auditStatus"), ""))) {
            return;
        }
        String executionId = stringValue(execution.get("executionId"), "");
        List<String> steps = stringList(execution.get("steps"));
        List<Map<String, Object>> actions = mapList(execution.get("domainActions"));
        String originalOperator = stringValue(execution.get("operator"), operator);
        String originalReason = stringValue(execution.get("trigger"), "");
        String executionMode = stringValue(execution.get("mode"), "regular");
        boolean emergencyExecution = "emergency".equalsIgnoreCase(executionMode);
        String failure = stringValue(notification.get("failure"), "");
        Map<String, Object> auditDetail = map(
                        "code", playbookCode,
                        "mode", executionMode,
                        "emergency", emergencyExecution,
                        "steps", steps,
                        "domainActions", actions,
                        "notificationDispatch", notification,
                        "failure", failure,
                        "reason", originalReason,
                        "recoveredAudit", true,
                        "idempotencyKey", idempotencyKey);
        if (partial) {
            auditRequiredWithResult(
                    "J4_SOP_PLAYBOOK_PARTIAL", "SOP_PLAYBOOK_EXECUTION", executionId,
                    originalOperator, "CRITICAL", "FAILED", auditDetail);
        } else {
            auditRequired(
                    "J4_SOP_PLAYBOOK_EXECUTED", "SOP_PLAYBOOK_EXECUTION", executionId,
                    originalOperator, emergencyExecution ? "CRITICAL" : "HIGH", auditDetail);
        }
        notification.put("auditStatus", "AUDITED");
        emergencyRepository.updateExecutionProgress(executionId, steps, notification, actions);
    }

    private ApiResult<Map<String, Object>> reconcileStaleExecution(
            Map<String, Object> execution,
            String playbookCode,
            String operator,
            String idempotencyKey) {
        String executionId = stringValue(execution.get("executionId"), "");
        List<String> steps = new ArrayList<>(stringList(execution.get("steps")));
        List<Map<String, Object>> actions = mapList(execution.get("domainActions"));
        Map<String, Object> notification = mutableMap(execution.get("notificationDispatch"));
        boolean partial = false;
        for (int index = 0; index < steps.size(); index++) {
            String status = steps.get(index);
            if ("pending".equals(status)) {
                steps.set(index, "skipped");
                markDomainActionStatus(actions, index + 1, "SKIPPED");
                partial = true;
                continue;
            }
            if (!"running".equals(status)) {
                partial = partial || "failed".equals(status) || "skipped".equals(status);
                continue;
            }
            int stepNumber = index + 1;
            Map<String, Object> action = actions.stream()
                    .filter(candidate -> intValue(candidate.get("step")) == stepNumber)
                    .findFirst()
                    .orElse(null);
            boolean completed = action != null && reconciledActionCompleted(action, executionId, notification);
            steps.set(index, completed ? "done" : "failed");
            if (action != null) {
                action.put("status", completed ? "DONE" : "FAILED");
            }
            partial = partial || !completed;
        }
        if (partial) {
            notification.put("failure", "J4_STALE_EXECUTION_RECONCILED");
        }
        emergencyRepository.updateExecutionProgress(
                executionId, steps, notification, actions);
        Map<String, Object> recovered = new LinkedHashMap<>(execution);
        recovered.put("steps", steps);
        recovered.put("notificationDispatch", notification);
        recovered.put("domainActions", actions);
        ensureExecutionOutcomeAudit(recovered, playbookCode, operator, idempotencyKey, partial);
        if (partial) {
            return ApiResult.fail(409, "J4_EXECUTION_PARTIAL:" + executionId + ":J4_STALE_EXECUTION_RECONCILED");
        }
        Map<String, Object> response = sopOverview().getData();
        response.put("updated", map(
                "executionId", executionId,
                "code", playbookCode,
                "idempotentReplay", true,
                "reconciled", true));
        return ApiResult.ok(response);
    }

    private boolean reconciledActionCompleted(
            Map<String, Object> action,
            String executionId,
            Map<String, Object> notification) {
        String domain = stringValue(action.get("domain"), "").toUpperCase(Locale.ROOT);
        if ("J1".equals(domain)) {
            String configKey = stringValue(action.get("configKey"), "");
            String gate = configKey.startsWith("killswitch.")
                    ? configKey.substring("killswitch.".length()) : "";
            String expectedToken = stringValue(action.get("ownershipToken"), "");
            String currentToken = emergencyRepository.settingValue(
                    "emergency.killswitch." + gate + ".lastChange").orElse("");
            String gateValue = emergencyRepository.settingValue(configKey).orElse("enabled");
            return StringUtils.hasText(gate)
                    && expectedToken.equals(currentToken)
                    && "disabled".equalsIgnoreCase(gateValue);
        }
        if ("I3".equals(domain)) {
            String campaignNo = stringValue(action.get("campaignNo"), "");
            Optional<NotificationEmergencyDispatchResult> dispatch =
                    notificationDispatchFacade.findEmergencyDispatch(campaignNo, executionId);
            if (dispatch.isEmpty()) {
                return false;
            }
            NotificationEmergencyDispatchResult value = dispatch.get();
            notification.put("required", true);
            notification.put("status", "DISPATCHED");
            notification.put("campaignNo", value.campaignNo());
            notification.put("name", value.name());
            notification.put("tier", value.tier());
            notification.put("audience", value.audience());
            notification.put("campaignStatus", value.status());
            notification.put("notificationCount", value.notificationCount());
            return true;
        }
        return false;
    }

    private List<Map<String, Object>> pendingDomainActions(PlaybookSeed seed, String execId, String operator) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (int index = 0; index < seed.seq().size(); index++) {
            Map<String, Object> step = seed.seq().get(index);
            String domain = stringValue(step.get("domain"), "").toUpperCase(Locale.ROOT);
            String action = stringValue(step.get("action"), "").replace("**", "").trim();
            String gate = SOP_J1_WITHDRAW_ACTION.equals(action) ? "withdraw"
                    : SOP_J1_GENESIS_ACTION.equals(action) ? "genesis" : "";
            if ("J1".equals(domain) && StringUtils.hasText(gate)) {
                actions.add(map(
                        "domain", domain,
                        "step", index + 1,
                        "action", action,
                        "configKey", "killswitch." + gate,
                        "beforeValue", "enabled",
                        "value", "disabled",
                        "valueType", "STRING",
                        "group", "admin_killswitch",
                        "ownershipToken", j4OwnershipToken(execId, gate),
                        "status", "PENDING",
                        "operator", operator));
            } else if ("I3".equals(domain) && SOP_I3_NOTIFY_ACTION.equals(action)) {
                actions.add(map(
                        "domain", domain,
                        "step", index + 1,
                        "action", action,
                        "campaignNo", seed.notifyCampaignNo(),
                        "status", "PENDING",
                        "operator", operator));
            }
        }
        return actions;
    }

    private void markDomainActionStatus(List<Map<String, Object>> actions, int step, String status) {
        actions.stream()
                .filter(action -> intValue(action.get("step")) == step)
                .forEach(action -> action.put("status", status));
    }

    private String j4OwnershipToken(String executionId, String gate) {
        return "J4_OWNERSHIP:" + executionId + ":" + gate;
    }

    private String safeJ4StepFailure(RuntimeException error) {
        String message = error instanceof BizException ? error.getMessage() : "J4_STEP_FAILED";
        return StringUtils.hasText(message) && message.matches("[A-Z0-9_:.-]{1,160}")
                ? message
                : "J4_STEP_FAILED";
    }

    private ApiResult<Map<String, Object>> rejectJ4(
            String operation,
            String resourceId,
            String operator,
            String reason,
            String idempotencyKey,
            ApiResult<Map<String, Object>> rejection) {
        auditRejectedRequiredInNewTransaction("J4_SOP_" + operation + "_REJECTED", "SOP_PLAYBOOK", resourceId,
                operator, "HIGH", map(
                        "result", "REJECTED",
                        "errorCode", rejection.getMessage(),
                        "businessDataChanged", false,
                        "reason", stringValue(reason, ""),
                        "idempotencyKey", stringValue(idempotencyKey, "")));
        return rejection;
    }

    private ApiResult<Map<String, Object>> validateExecutablePlaybook(PlaybookSeed seed, boolean production) {
        ApiResult<Map<String, Object>> definitionError = validatePlaybookDefinition(
                playbookRow(seed), emergencyRepository.playbooks(), seed.code());
        if (definitionError != null) {
            return definitionError;
        }
        if (production && (seed.draft() || !"active".equals(seed.state())
                || !isJ4DrillFresh(seed.lastDrill()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_PLAYBOOK_NOT_READY");
        }
        if (requiresI3Dispatch(seed)
                && notificationDispatchFacade.inspectEmergencyCampaign(seed.notifyCampaignNo()).isEmpty()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_NOTIFY_CAMPAIGN_NOT_READY");
        }
        return null;
    }

    private ApiResult<Map<String, Object>> validateJ4StepConfirmations(
            PlaybookSeed seed, SopPlaybookRunRequest request) {
        String triggerBasis = stringValue(request.triggerBasis(), "").trim();
        if (!GEO_TRIGGER_BASES.contains(triggerBasis)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_TRIGGER_BASIS_INVALID");
        }
        String triggerContext = stringValue(request.triggerContext(), "").trim();
        if (triggerContext.length() < 8 || triggerContext.length() > SOP_EXECUTION_TEXT_MAX_CHARS) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_TRIGGER_CONTEXT_INVALID");
        }
        List<SopStepConfirmationRequest> confirmations = request.stepConfirmations() == null
                ? List.of() : request.stepConfirmations();
        if (confirmations.size() != seed.seq().size()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_STEP_CONFIRMATIONS_REQUIRED");
        }
        for (int index = 0; index < seed.seq().size(); index++) {
            Map<String, Object> step = seed.seq().get(index);
            SopStepConfirmationRequest confirmation = confirmations.get(index);
            String expectedDomain = stringValue(step.get("domain"), "").toUpperCase(Locale.ROOT);
            String expectedRef = stringValue(step.get("ref"), "").toLowerCase(Locale.ROOT);
            if (confirmation == null
                    || confirmation.step() == null || confirmation.step() != index + 1
                    || !expectedDomain.equals(stringValue(confirmation.domain(), "").toUpperCase(Locale.ROOT))
                    || !expectedRef.equals(stringValue(confirmation.ref(), "").toLowerCase(Locale.ROOT))
                    || !Boolean.TRUE.equals(confirmation.confirmed())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                        "J4_STEP_CONFIRMATION_INVALID:" + (index + 1));
            }
        }
        return null;
    }

    private ApiResult<Map<String, Object>> validatePlaybookDefinition(
            Map<String, Object> row,
            List<Map<String, Object>> existingRows,
            String currentCode) {
        String name = stringValue(row.get("name"), "").trim();
        if (name.length() < 2 || name.length() > 128) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_PLAYBOOK_NAME_INVALID");
        }
        boolean duplicateName = existingRows.stream().anyMatch(existing ->
                !stringValue(existing.get("code"), "").equalsIgnoreCase(stringValue(currentCode, ""))
                        && name.equalsIgnoreCase(stringValue(existing.get("name"), "").trim()));
        if (duplicateName) {
            return ApiResult.fail(409, "J4_PLAYBOOK_NAME_CONFLICT");
        }
        if (!SOP_SCENES.contains(stringValue(row.get("scene"), ""))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_SCENE_INVALID");
        }
        String owner = stringValue(row.get("owner"), "").trim();
        if (!StringUtils.hasText(owner)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_OWNER_REQUIRED");
        }
        if (!SOP_OWNERS.contains(owner)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_OWNER_INVALID");
        }
        String sla = stringValue(row.get("sla"), "").trim();
        if (!StringUtils.hasText(sla)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_SLA_REQUIRED");
        }
        if (!isValidSopSla(sla)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_SLA_INVALID");
        }
        if (!StringUtils.hasText(stringValue(row.get("rollback"), ""))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ROLLBACK_PLAN_REQUIRED");
        }
        List<Map<String, Object>> sequence = mapList(row.get("sequence"));
        if (sequence.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_SEQUENCE_REQUIRED");
        }
        if (sequence.size() > SOP_EXECUTION_SEQUENCE_MAX_STEPS) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_SEQUENCE_TOO_LONG");
        }
        boolean actionTooLong = sequence.stream()
                .map(step -> stringValue(step.get("action"), ""))
                .anyMatch(action -> action.length() > SOP_EXECUTION_TEXT_MAX_CHARS);
        if (actionTooLong) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_TEXT_TOO_LONG");
        }
        Set<String> seenActions = new LinkedHashSet<>();
        for (Map<String, Object> step : sequence) {
            String domain = stringValue(step.get("domain"), "").toUpperCase(Locale.ROOT);
            String action = stringValue(step.get("action"), "").replace("**", "").trim();
            String ref = stringValue(step.get("ref"), "").trim().toLowerCase(Locale.ROOT);
            if (!SOP_EXECUTABLE_DOMAINS.contains(domain)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_NOT_EXECUTABLE:" + domain);
            }
            if (!StringUtils.hasText(action)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_REQUIRED");
            }
            if (containsRecoveryMarker(action)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_RECOVERY_ACTION_FORBIDDEN");
            }
            String expectedRef = expectedActionRef(domain, action);
            if (!StringUtils.hasText(expectedRef)
                    || (StringUtils.hasText(ref) && !expectedRef.equals(ref))) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_NOT_EXECUTABLE:" + domain);
            }
            if (!seenActions.add(domain + ":" + expectedRef)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                        "J4_ACTION_DUPLICATED:" + domain + ":" + expectedRef);
            }
            if ("J1".equals(domain) && !isSupportedJ1Action(action)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_NOT_EXECUTABLE:J1");
            }
            if ("I3".equals(domain) && !SOP_I3_NOTIFY_ACTION.equals(action)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_NOT_EXECUTABLE:I3");
            }
        }
        PlaybookSeed seed = playbookFromRow(row);
        if (requiresI3Dispatch(seed)) {
            if (!StringUtils.hasText(seed.notifyCampaignNo())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_NOTIFY_CAMPAIGN_NO_REQUIRED");
            }
            if (notificationDispatchFacade.inspectEmergencyCampaign(seed.notifyCampaignNo()).isEmpty()) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_NOTIFY_CAMPAIGN_NOT_READY");
            }
        }
        if (stringValue(seed.rollback(), "").length() > SOP_EXECUTION_TEXT_MAX_CHARS) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ROLLBACK_PLAN_TOO_LONG");
        }
        return null;
    }

    private boolean requiresI3Dispatch(PlaybookSeed seed) {
        return seed.seq().stream()
                .anyMatch(step -> "I3".equals(stringValue(step.get("domain"), "").toUpperCase(Locale.ROOT)));
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        int reasonLength = reason.trim().length();
        if (reasonLength < 8 || reasonLength > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), "REASON_LENGTH_INVALID");
        }
        return null;
    }

    private boolean isValidSopSla(String value) {
        var minutes = SOP_SLA_MINUTES.matcher(value);
        if (minutes.matches()) {
            int amount = Integer.parseInt(minutes.group(1));
            return amount >= 1 && amount <= 1440;
        }
        // 兼容已落库的早期“≤ 1h”格式；新表单只提交“正整数 分钟”。
        var hours = SOP_SLA_HOURS.matcher(value);
        if (hours.matches()) {
            int amount = Integer.parseInt(hours.group(1));
            return amount >= 1 && amount <= 24;
        }
        return false;
    }

    private boolean isJ4DrillFresh(String lastDrill) {
        if (!StringUtils.hasText(lastDrill) || "未演练".equals(lastDrill.trim())) {
            return false;
        }
        try {
            return !LocalDateTime.parse(lastDrill.trim(), TS)
                    .isBefore(LocalDateTime.now().minusDays(90));
        } catch (RuntimeException ignored) {
            try {
                return !LocalDateTime.parse(lastDrill.trim())
                        .isBefore(LocalDateTime.now().minusDays(90));
            } catch (RuntimeException invalidTimestamp) {
                return false;
            }
        }
    }

    private boolean containsRecoveryMarker(String action) {
        String normalized = action.toLowerCase(Locale.ROOT);
        return SOP_RECOVERY_MARKERS.stream().anyMatch(normalized::contains);
    }

    private boolean isSupportedJ1Action(String action) {
        return SOP_J1_WITHDRAW_ACTION.equals(action) || SOP_J1_GENESIS_ACTION.equals(action);
    }

    private String expectedActionRef(String domain, String action) {
        if ("J1".equals(domain)) {
            if (SOP_J1_WITHDRAW_ACTION.equals(action)) return "withdraw";
            if (SOP_J1_GENESIS_ACTION.equals(action)) return "genesis";
        }
        return "I3".equals(domain) && SOP_I3_NOTIFY_ACTION.equals(action) ? "campaign-notify" : "";
    }

    private Map<String, Object> inspectNotificationCampaign(PlaybookSeed seed) {
        if (!requiresI3Dispatch(seed)) {
            return map("required", false, "status", "SKIPPED");
        }
        return notificationDispatchFacade.inspectEmergencyCampaign(seed.notifyCampaignNo())
                .map(campaign -> map(
                        "required", true,
                        "status", "VALIDATED",
                        "campaignNo", campaign.campaignNo(),
                        "name", campaign.name(),
                        "tier", campaign.tier(),
                        "audience", campaign.audience(),
                        "campaignStatus", campaign.status()))
                .orElseThrow(() -> new IllegalStateException("J4_NOTIFY_CAMPAIGN_NOT_READY"));
    }

    private ApiResult<Map<String, Object>> requireTargetAuthorities(PlaybookSeed seed) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        for (Map<String, Object> step : seed.seq()) {
            String domain = stringValue(step.get("domain"), "").toUpperCase(Locale.ROOT);
            String required = switch (domain) {
                case "J1" -> "emergency_j1_gate_kill";
                case "I3" -> "content_i3_write";
                default -> "";
            };
            if (StringUtils.hasText(required) && !authorities.contains(required)) {
                return ApiResult.fail(403, "J4_TARGET_AUTHORITY_REQUIRED:" + domain + ":" + required);
            }
        }
        return null;
    }

    private boolean hasAuthenticatedAuthority(String required) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .anyMatch(required::equals);
    }

    private boolean isReversibleExecution(Map<String, Object> execution) {
        List<String> steps = stringList(execution.get("steps"));
        if (steps.isEmpty() || steps.stream().anyMatch(
                status -> !Set.of("done", "failed", "skipped", "rolled_back").contains(status))) {
            return false;
        }
        Map<String, Object> notification = mutableMap(execution.get("notificationDispatch"));
        return "AUDITED".equals(stringValue(notification.get("auditStatus"), ""))
                && !reversibleJ1Actions(mapList(execution.get("domainActions"))).isEmpty();
    }

    private ApiResult<Map<String, Object>> requireGeoTriggerBasis(String triggerBasis) {
        if (!StringUtils.hasText(triggerBasis)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRIGGER_BASIS_REQUIRED");
        }
        if (!GEO_TRIGGER_BASES.contains(triggerBasis.trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRIGGER_BASIS_INVALID");
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> idempotent(
            String scope,
            String idempotencyKey,
            String requestHash,
            Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                scope, idempotencyKey, requestHash, ApiResult.class, (Supplier) action);
    }

    private ApiResult<Map<String, Object>> idempotentJ4(
            String scope,
            String idempotencyKey,
            String requestHash,
            Supplier<ApiResult<Map<String, Object>>> action,
            String operation,
            String resourceId,
            String operator,
            String reason) {
        try {
            return idempotent(scope, idempotencyKey, requestHash, action);
        } catch (BizException ex) {
            if (!GEO_IDEMPOTENCY_REJECTION_CODES.contains(ex.getMessage())) {
                throw ex;
            }
            return rejectJ4(operation, resourceId, operator, reason, idempotencyKey,
                    ApiResult.fail(ex.getCode(), ex.getMessage()));
        }
    }

    private ApiResult<Map<String, Object>> idempotentJ3(
            String scope,
            String idempotencyKey,
            String requestHash,
            Supplier<ApiResult<Map<String, Object>>> action,
            String auditAction,
            String resourceType,
            String resourceId,
            String operator,
            Object attempted,
            String reason) {
        try {
            return idempotent(scope, idempotencyKey, requestHash, action);
        } catch (BizException ex) {
            if (!GEO_IDEMPOTENCY_REJECTION_CODES.contains(ex.getMessage())) {
                throw ex;
            }
            auditRejectedJ3(auditAction, resourceType, resourceId, operator,
                    ex.getMessage(), Map.of(), attempted, idempotencyKey, reason);
            return ApiResult.fail(ex.getCode(), ex.getMessage());
        }
    }

    private ApiResult<Map<String, Object>> idempotentGeo(
            String scope,
            String idempotencyKey,
            String requestHash,
            String resourceType,
            String resourceId,
            String operator,
            String riskLevel,
            Object currentState,
            Object attemptedState,
            String reason,
            Supplier<ApiResult<Map<String, Object>>> action) {
        try {
            return idempotent(scope, idempotencyKey, requestHash, action);
        } catch (BizException ex) {
            if (!GEO_IDEMPOTENCY_REJECTION_CODES.contains(ex.getMessage())) {
                throw ex;
            }
            return rejectGeoCommand(ex.getCode(), ex.getMessage(), resourceType, resourceId, operator, riskLevel,
                    currentState, attemptedState, idempotencyKey, reason);
        }
    }

    private String requestHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("J2_REQUEST_HASH_FAILED", ex);
        }
    }

    private String authenticatedOperator(String fallback) {
        String resolved = AdminActorResolver.resolve(fallback);
        return StringUtils.hasText(resolved) ? resolved.trim() : "system";
    }

    private boolean sameAdminActor(Object storedActor, String currentActor) {
        return canonicalAdminActor(storedActor).equals(canonicalAdminActor(currentActor));
    }

    private String canonicalAdminActor(Object actor) {
        String value = stringValue(actor, "").trim();
        return value.startsWith("admin:") ? value.substring("admin:".length()) : value;
    }

    private String publishGeoChange(
            String scope,
            String target,
            Object before,
            Object after,
            String operator,
            String reason,
            String triggerBasis) {
        return outboxService.publish("KILL_SWITCH", "geo-block", "ADMIN_KILLSWITCH_TOGGLED", map(
                "key", "geo-block",
                "scope", scope,
                "target", target,
                "before", before,
                "after", after,
                "operator", operator,
                "reason", reason == null ? "" : reason.trim(),
                "triggerBasis", triggerBasis == null ? "" : triggerBasis.trim(),
                "audienceRole", "SUPER_ADMIN",
                "occurredAt", LocalDateTime.now().toString()));
    }

    private int statusRank(String status) {
        return switch (status) {
            case "limited" -> 1;
            case "blocked" -> 2;
            default -> 0;
        };
    }

    private String countryStatus(String countryCode) {
        return emergencyRepository.geoCountryPolicies().stream()
                .filter(row -> countryCode.equalsIgnoreCase(stringValue(row.get("cc"), "")))
                .map(row -> stringValue(row.get("status"), "allowed").toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse("allowed");
    }

    private List<String> countryCodesByStatus(String status) {
        return emergencyRepository.geoCountryPolicies().stream()
                .filter(row -> status.equalsIgnoreCase(stringValue(row.get("status"), "")))
                .map(row -> stringValue(row.get("cc"), ""))
                .filter(StringUtils::hasText)
                .sorted()
                .toList();
    }

    private List<Map<String, Object>> countryOptions(Map<String, Map<String, Object>> impacts) {
        return ISO_COUNTRIES.stream()
                .sorted(Comparator.comparing(code -> new Locale("", code)
                        .getDisplayCountry(Locale.SIMPLIFIED_CHINESE)))
                .map(code -> {
                    Map<String, Object> impact = impacts.getOrDefault(code, Map.of());
                    return map(
                            "value", code,
                            "label", new Locale("", code).getDisplayCountry(Locale.SIMPLIFIED_CHINESE),
                            "source", "JDK ISO 3166-1 alpha-2",
                            "activeUsers", intValue(impact.get("activeUsers")),
                            "walletUsdt", stringValue(impact.get("walletUsdt"), "0"));
                })
                .toList();
    }

    private List<Map<String, Object>> countryViews(Map<String, Map<String, Object>> impacts) {
        return emergencyRepository.geoCountryPolicies().stream()
                .filter(row -> !"allowed".equals(row.get("status")))
                .map(row -> {
                    Map<String, Object> view = new LinkedHashMap<>(row);
                    Map<String, Object> impact = impacts.getOrDefault(stringValue(row.get("cc"), ""), Map.of());
                    view.put("activeUsers", intValue(impact.get("activeUsers")));
                    view.put("walletUsdt", stringValue(impact.get("walletUsdt"), "0"));
                    return view;
                })
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("status"))))
                .toList();
    }

    private Map<String, Map<String, Object>> countryImpactMap() {
        return emergencyRepository.geoCountryImpacts().stream()
                .filter(row -> StringUtils.hasText(stringValue(row.get("cc"), "")))
                .collect(Collectors.toMap(
                        row -> stringValue(row.get("cc"), "").toUpperCase(Locale.ROOT),
                        row -> row,
                        (left, ignored) -> left,
                        LinkedHashMap::new));
    }

    private Map<String, Object> countryView(String countryCode) {
        String cc = normalizeCountry(countryCode);
        Optional<Map<String, Object>> policy = emergencyRepository.geoCountryPolicies().stream()
                .filter(row -> cc.equals(row.get("cc")))
                .findFirst();
        String status = policy.map(row -> stringValue(row.get("status"), "allowed")).orElse("allowed");
        String name = policy.map(row -> stringValue(row.get("name"), ""))
                .filter(StringUtils::hasText)
                .orElse(cc);
        String reason = policy.map(row -> stringValue(row.get("reason"), "")).orElse("");
        Map<String, Object> impact = countryImpactMap().getOrDefault(cc, Map.of());
        return map(
                "cc", cc,
                "name", name,
                "status", status,
                "reason", reason,
                "operator", policy.map(row -> stringValue(row.get("operator"), "")).orElse(""),
                "updatedAt", policy.map(row -> stringValue(row.get("updatedAt"), "")).orElse(""),
                "activeUsers", intValue(impact.get("activeUsers")),
                "walletUsdt", stringValue(impact.get("walletUsdt"), "0"));
    }

    private void writeCountryStatus(String countryCode, String status, String reason, String operator) {
        String cc = normalizeCountry(countryCode);
        String name = emergencyRepository.geoCountryPolicies().stream()
                .filter(row -> cc.equals(row.get("cc")))
                .map(row -> stringValue(row.get("name"), ""))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElseGet(() -> new Locale("", cc).getDisplayCountry(Locale.SIMPLIFIED_CHINESE));
        emergencyRepository.upsertGeoCountryPolicy(cc, name, status, reason, operator);
    }

    private String normalizeCountry(String raw) {
        String country = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRY.matcher(country).matches() || !ISO_COUNTRIES.contains(country)) {
            throw new IllegalArgumentException("COUNTRY_CODE_INVALID");
        }
        return country;
    }

    private String normalizeGeoStatus(String raw) {
        String status = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (status) {
            case "blocked", "limited", "allowed" -> status;
            default -> throw new IllegalArgumentException("GEO_STATUS_INVALID");
        };
    }

    private List<String> normalizeCountries(List<String> rawCountries) {
        if (rawCountries == null) {
            return List.of();
        }
        LinkedHashSet<String> countries = new LinkedHashSet<>();
        for (String country : rawCountries) {
            if (StringUtils.hasText(country)) {
                countries.add(normalizeCountry(country));
            }
        }
        return countries.stream().sorted().toList();
    }

    private List<Map<String, Object>> geoEndpoints() {
        Map<String, Map<String, Object>> catalogs = emergencyRepository.geoEndpointCatalogs().stream()
                .collect(Collectors.toMap(
                        row -> stringValue(row.get("endpointKey"), ""),
                        row -> row,
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        Map<String, Integer> endpointHits = emergencyRepository.geoEndpointHits();
        Map<String, List<Map<String, Object>>> grouped = emergencyRepository.geoEndpointPolicies().stream()
                .collect(Collectors.groupingBy(row -> stringValue(row.get("endpointKey"), ""), LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            catalogs.putIfAbsent(entry.getKey(), entry.getValue().get(0));
        }
        return catalogs.entrySet().stream()
                .map(entry -> {
                    List<Map<String, Object>> rows = grouped.getOrDefault(entry.getKey(), List.of());
                    String source = rows.stream()
                            .map(row -> stringValue(row.get("source"), ""))
                            .filter(StringUtils::hasText)
                            .findFirst()
                            .orElseGet(() -> "ACTIVE".equalsIgnoreCase(stringValue(entry.getValue().get("status"), ""))
                                    ? "derived" : "pending");
                    String reason = rows.stream()
                            .map(row -> stringValue(row.get("reason"), ""))
                            .filter(StringUtils::hasText)
                            .findFirst()
                            .orElse("");
                    List<String> countries = rows.stream()
                            .map(row -> stringValue(row.get("countryCode"), ""))
                            .filter(StringUtils::hasText)
                            .toList();
                    return endpointView(entry.getValue(), countries, source, reason, endpointHits.getOrDefault(entry.getKey(), 0));
                })
                .toList();
    }

    private Map<String, Object> geoEdge() {
        String source = emergencyRepository.settingValue(GEO_EDGE_SOURCE)
                .orElse(GeoEdgeSourceRegistry.DEFAULT_SOURCE);
        boolean sourceKnown = GeoEdgeSourceRegistry.supports(source);
        GeoEdgeHealthMonitor.Snapshot health = geoEdgeHealthMonitor.snapshot(source);
        List<Map<String, Object>> metrics = new ArrayList<>(emergencyRepository.geoEdgeMetrics());
        metrics.add(map(
                "key", "地区解析状态",
                "value", sourceKnown ? edgeHealthLabel(health) : "判定源未登记",
                "tone", sourceKnown && health.healthy() ? "ok" : sourceKnown && health.sampleCount() == 0 ? "warn" : "danger"));
        metrics.add(map(
                "key", "请求头判定延迟 P95",
                "value", !sourceKnown ? "不可用" : health.sampleCount() == 0 ? "待采样" : health.p95Ms() + " ms",
                "tone", !sourceKnown ? "danger" : health.healthy() ? "ok" : "warn"));
        metrics.add(map(
                "key", "地区解析失败率",
                "value", !sourceKnown ? "不可用" : health.sampleCount() == 0 ? "待采样" : String.format(Locale.ROOT, "%.2f%%", health.failureRatePct()),
                "tone", sourceKnown && health.healthy() ? "ok" : "danger"));
        return map(
                "source", source,
                "sourceKnown", sourceKnown,
                "sources", GeoEdgeSourceRegistry.options(geoEdgeHealthMonitor),
                "healthStatus", sourceKnown ? health.status() : "invalid",
                "healthy", sourceKnown && health.healthy(),
                "metrics", metrics);
    }

    private String edgeHealthLabel(GeoEdgeHealthMonitor.Snapshot health) {
        return switch (health.status()) {
            case "healthy" -> "正常";
            case "degraded" -> "异常";
            case "stale" -> "已过期";
            default -> "待采样";
        };
    }

    private List<Map<String, Object>> geoHits() {
        return emergencyRepository.geoHits();
    }

    private Map<String, Object> tamperAlertConfig() {
        int threshold = emergencyRepository.settingValue(TAMPER_THRESHOLD)
                .map(Integer::parseInt)
                .orElse(10);
        boolean feedK4 = emergencyRepository.settingValue(TAMPER_FEED_K4)
                .map(Boolean::parseBoolean)
                .orElse(true);
        return map("threshold", threshold, "label", threshold + " 次 / 24h", "feedK4", feedK4);
    }

    private Map<String, Object> tamperAlertConfig(Map<String, String> settings) {
        int threshold = Optional.ofNullable(settings.get(TAMPER_THRESHOLD))
                .filter(StringUtils::hasText)
                .map(Integer::parseInt)
                .orElse(10);
        boolean feedK4 = Optional.ofNullable(settings.get(TAMPER_FEED_K4))
                .filter(StringUtils::hasText)
                .map(Boolean::parseBoolean)
                .orElse(true);
        return map("threshold", threshold, "label", threshold + " 次 / 24h", "feedK4", feedK4);
    }

    private Map<String, Object> tamperTrend(LocalDateTime now) {
        return emergencyRepository.tamperTrend(now);
    }

    private TamperRange tamperRange(String window, LocalDateTime endAt) {
        String label = StringUtils.hasText(window) ? window.trim().toLowerCase(Locale.ROOT) : "24h";
        return switch (label) {
            case "24h" -> new TamperRange(label, endAt.minusHours(24), endAt);
            case "7d" -> new TamperRange(label, endAt.minusDays(7), endAt);
            case "30d" -> new TamperRange(label, endAt.minusDays(30), endAt);
            default -> null;
        };
    }

    private int scaledTamperThreshold(int thresholdPer24h, String window) {
        int multiplier = switch (window) {
            case "7d" -> 7;
            case "30d" -> 30;
            default -> 1;
        };
        return Math.multiplyExact(thresholdPer24h, multiplier);
    }

    private List<Map<String, Object>> tamperPaths(TamperRange range) {
        return emergencyRepository.tamperPaths(range.startAt(), range.endAt());
    }

    private List<Map<String, Object>> tamperAccounts(TamperRange range, int threshold) {
        return emergencyRepository.tamperAccounts(range.startAt(), range.endAt(), threshold).stream()
                .map(this::tamperAccountView)
                .toList();
    }

    private Map<String, Object> sevenDayThresholdPreview(LocalDateTime now) {
        TamperRange range = tamperRange("7d", now);
        List<Map<String, Object>> distribution = emergencyRepository.tamperAccountFrequencyDistribution(
                range.startAt(), range.endAt());
        Map<String, Object> preview = new LinkedHashMap<>();
        for (int threshold = 1; threshold <= 100; threshold++) {
            int effectiveThreshold = scaledTamperThreshold(threshold, "7d");
            int accounts = distribution.stream()
                    .filter(row -> intValue(row.get("eventCount")) >= effectiveThreshold)
                    .mapToInt(row -> intValue(row.get("accountCount")))
                    .sum();
            preview.put(String.valueOf(threshold), accounts);
        }
        return preview;
    }

    private Map<String, Object> tamperAccountView(Map<String, Object> row) {
        String userCode = firstText(row.get("userCode"), row.get("userNo"), row.get("uid"));
        userCode = toUserCode(userCode);
        boolean fedToK4 = boolValue(row.get("fedToK4"));
        boolean b5Triggered = boolValue(row.get("b5Triggered"));
        int count = intValue(row.get("count"));
        Map<String, Object> account = tamperAccount(
                userCode, count,
                stringValue(row.get("k4"), fedToK4 ? "+" + count : "未喂送"),
                stringValue(row.get("last"), ""),
                stringList(row.get("paths")),
                stringValue(row.get("cluster"), ""));
        account.put("fedToK4", fedToK4);
        account.put("b5Triggered", b5Triggered);
        account.put("alertState", firstText(row.get("alertState"), fedToK4 && b5Triggered ? "escalated" : "flagged"));
        return account;
    }

    private Map<String, Object> tamperAccount(String userCode, int count, String k4, String last, List<String> paths, String cluster) {
        String normalizedUserCode = toUserCode(userCode);
        return map("userCode", normalizedUserCode, "userNo", normalizedUserCode, "count", count, "k4", k4, "last", last, "paths", paths, "cluster", cluster);
    }

    private Map<String, Object> playbookView(PlaybookSeed seed) {
        boolean drillFresh = isJ4DrillFresh(seed.lastDrill());
        boolean campaignReady = !requiresI3Dispatch(seed)
                || notificationDispatchFacade.inspectEmergencyCampaign(seed.notifyCampaignNo()).isPresent();
        boolean executionReady = !seed.draft() && "active".equals(seed.state()) && drillFresh && campaignReady;
        String readinessReason = seed.draft() || !"active".equals(seed.state())
                ? "待演练"
                : !drillFresh
                        ? "演练已超过90天，请重新演练"
                        : !campaignReady ? "绑定通知活动不可下发，请更换活动后重新演练" : "";
        return map(
                "code", seed.code(),
                "name", seed.name(),
                "scene", seed.scene(),
                "emergency", seed.emergency(),
                "sla", seed.sla(),
                "state", executionReady ? "active" : "todo",
                "executionReady", executionReady,
                "drillFresh", drillFresh,
                "campaignReady", campaignReady,
                "readinessReason", readinessReason,
                "owner", seed.owner(),
                "lastDrill", seed.lastDrill(),
                "sequence", seed.seq(),
                "notifyCampaignNo", seed.notifyCampaignNo(),
                "notifyTemplate", seed.notifyTemplate(),
                "rollback", seed.rollback(),
                "drillRequired", seed.drillRequired(),
                "draft", seed.draft(),
                "version", seed.version(),
                "customSummary", seed.summary(),
                "lastExecution", seed.lastExecution());
    }

    private Map<String, Object> playbookDraftRow(String code, SopPlaybookCreateRequest request) {
        return map(
                "code", code,
                "name", stringValue(request.name(), "").trim(),
                "scene", stringValue(request.scene(), ""),
                "emergency", request.emergencyTrack() != null && request.emergencyTrack(),
                "sla", stringValue(request.sla(), ""),
                "state", "todo",
                "owner", stringValue(request.owner(), ""),
                "lastDrill", "未演练",
                "sequence", parseActionSequence(request.actionSeq()),
                "notifyCampaignNo", stringValue(request.notifyCampaignNo(), ""),
                "notifyTemplate", stringValue(request.notifyTemplate(), ""),
                "rollback", stringValue(request.rollback(), ""),
                "drillRequired", true,
                "draft", true,
                "version", "");
    }

    private Map<String, Object> previewPlaybookUpdate(PlaybookSeed seed, SopPlaybookUpdateRequest request) {
        boolean drillRequired = true;
        List<Map<String, Object>> sequence = request.actionSeq() == null
                ? seed.seq()
                : parseActionSequence(request.actionSeq());
        return map(
                "code", seed.code(),
                "name", request.name() == null ? seed.name() : request.name().trim(),
                "scene", request.scene() == null ? seed.scene() : request.scene().trim(),
                "emergency", request.emergencyTrack() == null ? seed.emergency() : request.emergencyTrack(),
                "sla", request.sla() == null ? seed.sla() : request.sla().trim(),
                "state", "todo",
                "owner", request.owner() == null ? seed.owner() : request.owner().trim(),
                "lastDrill", "未演练",
                "sequence", sequence,
                "notifyCampaignNo", request.notifyCampaignNo() == null ? seed.notifyCampaignNo() : request.notifyCampaignNo().trim(),
                "notifyTemplate", request.notifyTemplate() == null ? seed.notifyTemplate() : request.notifyTemplate().trim(),
                "rollback", request.rollback() == null ? seed.rollback() : request.rollback().trim(),
                "drillRequired", drillRequired,
                "draft", true,
                "version", seed.version());
    }

    private Map<String, Object> updatePlaybookRow(
            String code,
            SopPlaybookUpdateRequest request,
            Map<String, Object> candidate,
            String operator) {
        List<Map<String, Object>> sequence = mapList(candidate.get("sequence"));
        boolean updated = emergencyRepository.updatePlaybook(
                code,
                request.name(),
                request.scene(),
                request.emergencyTrack(),
                request.sla(),
                stringValue(candidate.get("state"), "todo"),
                request.owner(),
                request.notifyCampaignNo(),
                request.notifyTemplate(),
                request.rollback(),
                request.drillRequired(),
                request.summary(),
                sequence,
                boolValue(candidate.get("draft")),
                request.version().trim(),
                operator);
        if (!updated) {
            return null;
        }
        return emergencyRepository.playbook(code).orElseGet(() -> playbookRow(playbookSeed(code)));
    }

    private List<Map<String, Object>> executionSeeds() {
        return emergencyRepository.executions(SOP_EXECUTION_HISTORY_MAX_ROWS);
    }

    private Map<String, Object> execution(String ts, String code, String name, String trigger, String mode, List<String> steps, String operator, String roleGate) {
        return map("timestamp", ts, "code", code, "name", name, "trigger", trigger, "mode", mode, "steps", steps, "operator", operator, "roleGate", roleGate);
    }

    private Map<String, Object> endpointCatalog(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return emergencyRepository.geoEndpointCatalog(normalized)
                .orElseThrow(() -> new IllegalArgumentException("GEO_ENDPOINT_NOT_FOUND"));
    }

    private Map<String, Object> endpointView(Map<String, Object> endpoint, List<String> countries, String source, String reason, int hits) {
        return map(
                "key", endpoint.get("endpointKey"),
                "endpoint", endpoint.get("endpointPath"),
                "label", endpoint.get("label"),
                "biz", endpoint.get("biz"),
                "domain", endpoint.get("domain"),
                "countries", countries,
                "source", source,
                "configurable", !"pending".equals(source),
                "sourceLabel", switch (source) {
                    case "explicit" -> "单独设定";
                    case "derived" -> "继承全局";
                    default -> "待实装确认";
                },
                "sourceDescription", reason,
                "hits", hits);
    }

    private List<PlaybookSeed> playbookSeeds() {
        return emergencyRepository.playbooks().stream()
                .map(this::playbookFromRow)
                .toList();
    }

    private Map<String, Object> playbookRow(PlaybookSeed seed) {
        return map(
                "code", seed.code(),
                "name", seed.name(),
                "scene", seed.scene(),
                "emergency", seed.emergency(),
                "sla", seed.sla(),
                "state", seed.state(),
                "owner", seed.owner(),
                "lastDrill", seed.lastDrill(),
                "sequence", seed.seq(),
                "notifyCampaignNo", seed.notifyCampaignNo(),
                "notifyTemplate", seed.notifyTemplate(),
                "rollback", seed.rollback(),
                "drillRequired", seed.drillRequired(),
                "draft", seed.draft(),
                "version", seed.version());
    }

    private PlaybookSeed playbookFromRow(Map<String, Object> row) {
        String code = stringValue(row.get("code"), "SOP-UNKNOWN").toUpperCase(Locale.ROOT);
        List<Map<String, Object>> sequence = mapList(row.get("sequence"));
        return new PlaybookSeed(
                code,
                stringValue(row.get("name"), code),
                stringValue(row.get("scene"), "监管点名"),
                boolValue(row.get("emergency")),
                stringValue(row.get("sla"), "≤ 1h"),
                stringValue(row.get("state"), "todo"),
                stringValue(row.get("owner"), "未分配"),
                stringValue(row.get("lastDrill"), "未演练"),
                sequence,
                stringValue(row.get("notifyCampaignNo"), ""),
                firstText(row.get("notifyTemplate"), firstI3Notify(sequence)),
                stringValue(row.get("rollback"), ""),
                row.containsKey("drillRequired") ? boolValue(row.get("drillRequired")) : true,
                boolValue(row.get("draft")),
                stringValue(row.get("version"), ""),
                stringValue(row.get("customSummary"), ""),
                stringValue(row.get("lastExecution"), ""));
    }

    private PlaybookSeed playbookSeed(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return findPlaybookSeed(normalized)
                .orElseThrow(() -> new IllegalArgumentException("SOP_PLAYBOOK_NOT_FOUND"));
    }

    private Optional<PlaybookSeed> findPlaybookSeed(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return playbookSeeds().stream()
                .filter(seed -> seed.code().equals(normalized))
                .findFirst();
    }

    private List<Map<String, Object>> defaultActionOptions() {
        return List.of(
                actionOption("J1", "熔断提现通道", "withdraw", true, "暂停用户提现，用于挤兑或对账缺口止血"),
                actionOption("J1", "熔断 Genesis 交易", "genesis", true, "暂停 Genesis 交易或二级市场入口"),
                actionOption("I3", "发送通知模板", "campaign-notify", false, "使用已排期的通知活动通知用户、法务、财务或超管"));
    }

    private Map<String, Object> actionOption(String domain, String action, String ref, boolean approve, String description) {
        return map(
                "value", domain + ":" + ref,
                "domain", domain,
                "action", action,
                "ref", ref,
                "approve", approve,
                "description", description);
    }

    private List<Map<String, Object>> defaultRollbackOptions() {
        return List.of(
                rollbackOption("root-cause-standard", "逐步恢复已关停入口", "通用", "HIGH", "根因消除并完成复核后,按本次执行快照逐项恢复 J1 入口;恢复不走应急轨"));
    }

    private Map<String, Object> rollbackOption(String value, String label, String scene, String riskLevel, String plan) {
        return map(
                "value", value,
                "label", label,
                "scene", scene,
                "riskLevel", riskLevel,
                "plan", plan);
    }

    private String firstI3Notify(List<Map<String, Object>> sequence) {
        return sequence.stream()
                .filter(row -> "I3".equals(String.valueOf(row.get("domain"))))
                .map(row -> stringValue(row.get("action"), "").replace("**", ""))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
    }

    private String nextDraftCode(List<Map<String, Object>> rows) {
        int max = 0;
        for (Map<String, Object> row : rows) {
            String code = stringValue(row.get("code"), "");
            if (!code.startsWith("SOP-CUSTOM-")) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(code.substring("SOP-CUSTOM-".length())));
            } catch (NumberFormatException ignored) {
                max = Math.max(max, 0);
            }
        }
        return "SOP-CUSTOM-" + (max + 1);
    }

    private List<Map<String, Object>> parseActionSequence(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(line -> {
                    String[] parts = line.split("[·|｜]", 3);
                    String domain = parts.length > 1 ? parts[0].trim().toUpperCase(Locale.ROOT) : "J4";
                    String action = parts.length > 1 ? parts[1].trim() : line;
                    if (!domain.matches("^[A-Z]\\d?$")) {
                        domain = "J4";
                        action = line;
                    }
                    String ref = parts.length > 2
                            ? parts[2].trim().toLowerCase(Locale.ROOT)
                            : expectedActionRef(domain, action);
                    return map("domain", domain, "action", action, "approve", !"I3".equals(domain), "ref", ref);
                })
                .toList();
    }

    private Optional<String> activeValue(String configKey) {
        return emergencyRepository.settingValue(configKey);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && StringUtils.hasText(string)) {
            return Integer.parseInt(string.trim());
        }
        return 0;
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String string) {
            String normalized = string.trim();
            return "1".equals(normalized) || Boolean.parseBoolean(normalized);
        }
        return false;
    }

    private String booleanStateLabel(Object value) {
        if (value == null) {
            return "未知";
        }
        return boolValue(value) ? "开启" : "关闭";
    }

    private String stringValue(Object value, String fallback) {
        if (value instanceof String string && StringUtils.hasText(string)) {
            return string.trim();
        }
        if (value != null) {
            return String.valueOf(value);
        }
        return fallback;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value, "");
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String toUserCode(String raw) {
        String text = stringValue(raw, "").replace("-", "").toUpperCase(Locale.ROOT);
        if (!StringUtils.hasText(text)) {
            return "U00000000";
        }
        String digits = text.replaceAll("\\D", "");
        if (StringUtils.hasText(digits)) {
            if (digits.length() > 8) {
                digits = digits.substring(digits.length() - 8);
            }
            return "U" + "0".repeat(8 - digits.length()) + digits;
        }
        return text;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> stringValue(item, ""))
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> row = new LinkedHashMap<>();
                rawMap.forEach((key, val) -> row.put(String.valueOf(key), val));
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Object> mutableMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private List<Object> mapListValues(Map<?, ?> source, String key) {
        if (source == null) {
            return List.of();
        }
        Object value = source.get(key);
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private void audit(String action, String resourceType, String resourceId, String operator, String riskLevel, Map<String, Object> detail) {
        auditLogService.record(auditRequest(action, resourceType, resourceId, operator, riskLevel, detail));
    }

    private void auditRequiredInNewTransaction(String action, String resourceType, String resourceId, String operator,
                                               String riskLevel, Map<String, Object> detail) {
        auditLogService.recordRequiredInNewTransaction(
                auditRequest(action, resourceType, resourceId, operator, riskLevel, detail));
    }

    private void auditRejectedRequiredInNewTransaction(
            String action, String resourceType, String resourceId, String operator,
            String riskLevel, Map<String, Object> detail) {
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("REJECTED")
                .riskLevel(riskLevel)
                .detail(detail)
                .build());
    }

    private void auditRequired(String action, String resourceType, String resourceId, String operator,
                               String riskLevel, Map<String, Object> detail) {
        auditLogService.recordRequired(auditRequest(action, resourceType, resourceId, operator, riskLevel, detail));
    }

    private void auditRequiredWithResult(
            String action, String resourceType, String resourceId, String operator,
            String riskLevel, String result, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result(result)
                .riskLevel(riskLevel)
                .detail(detail)
                .build());
    }

    private AuditLogWriteRequest auditRequest(String action, String resourceType, String resourceId, String operator,
                                              String riskLevel, Map<String, Object> detail) {
        return AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel(riskLevel)
                .detail(detail)
                .build();
    }

    private Map<String, Object> maskedTamperAccount(Map<String, Object> row) {
        Map<String, Object> masked = new LinkedHashMap<>(row);
        masked.put("userCode", maskUserCode(stringValue(row.get("userCode"), "")));
        masked.put("userNo", masked.get("userCode"));
        return masked;
    }

    private String tamperReportCsv(String window, List<Map<String, Object>> paths, List<Map<String, Object>> accounts) {
        List<String> lines = new ArrayList<>();
        lines.add("记录类型,时间窗,用户编码,篡改路径,拦截数,涉及账户数,K4状态,B5状态,告警状态,最近拦截");
        for (Map<String, Object> row : paths) {
            lines.add(String.join(",",
                    "路径汇总", csv(window), "", csv(stringValue(row.get("name"), stringValue(row.get("id"), ""))),
                    csv(row.get("count")), csv(row.get("accounts")), "", "", "", ""));
        }
        for (Map<String, Object> row : accounts) {
            lines.add(String.join(",",
                    "账户告警", csv(window), csv(maskUserCode(stringValue(row.get("userCode"), ""))),
                    csv(String.join("|", stringList(row.get("paths")))), csv(row.get("count")), "",
                    csv(boolValue(row.get("fedToK4")) ? "已喂送" : "未喂送"),
                    csv(boolValue(row.get("b5Triggered")) ? "已触发" : "未触发"),
                    csv(localizedTamperAlertState(row.get("alertState"))), csv(row.get("last"))));
        }
        return String.join("\r\n", lines);
    }

    private String localizedTamperAlertState(Object state) {
        return switch (stringValue(state, "").trim().toLowerCase(Locale.ROOT)) {
            case "normal" -> "正常";
            case "flagged" -> "已告警";
            case "escalated" -> "已升级处置";
            default -> "未知";
        };
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && (text.charAt(0) == '=' || text.charAt(0) == '+' || text.charAt(0) == '-'
                || text.charAt(0) == '@' || text.charAt(0) == '\t' || text.charAt(0) == '\r')) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String maskUserCode(String userCode) {
        if (!StringUtils.hasText(userCode) || userCode.length() <= 4) {
            return "****";
        }
        return userCode.substring(0, 1) + "****" + userCode.substring(userCode.length() - 4);
    }

    private void auditRejectedJ3(
            String action, String resourceType, String resourceId, String operator,
            String rejectionCode, Object before, Object attempted,
            String idempotencyKey, String reason) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(map(
                        "rejectionCode", rejectionCode,
                        "before", before,
                        "attempted", attempted,
                        "reason", StringUtils.hasText(reason) ? reason.trim() : "",
                        "idempotencyKey", StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : "",
                        "businessDataChanged", false))
                .build());
    }

    private void auditFailedJ3(
            String action, String resourceType, String resourceId, String operator,
            String idempotencyKey, String reason, Object attempted,
            RuntimeException failure) {
        try {
            auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .bizNo(resourceId)
                    .actorType("ADMIN")
                    .actorUsername(operator)
                    .result("FAILED")
                    .riskLevel("HIGH")
                    .detail(map(
                            "failureCode", "J3_UNEXPECTED_FAILURE",
                            "failureType", failure.getClass().getSimpleName(),
                            "attempted", attempted,
                            "reason", StringUtils.hasText(reason) ? reason.trim() : "",
                            "idempotencyKey", StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : "",
                            "businessDataChanged", false))
                    .build());
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
        }
    }

    private record TamperRange(String label, LocalDateTime startAt, LocalDateTime endAt) {
        TamperRange previous() {
            Duration duration = Duration.between(startAt, endAt);
            return new TamperRange(label, startAt.minus(duration), startAt);
        }
    }

    private ApiResult<Map<String, Object>> rejectGeoCommand(
            int httpStatus,
            String rejectionCode,
            String resourceType,
            String resourceId,
            String operator,
            String riskLevel,
            Object currentState,
            Object attemptedState,
            String idempotencyKey,
            String reason) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("J2_GEO_COMMAND_REJECTED")
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("REJECTED")
                .riskLevel(riskLevel)
                .detail(map(
                        "rejectionCode", rejectionCode,
                        "currentState", currentState,
                        "attemptedState", attemptedState,
                        "reason", StringUtils.hasText(reason) ? reason.trim() : "",
                        "idempotencyKey", StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : "",
                        "businessDataChanged", false))
                .build());
        return ApiResult.fail(httpStatus, rejectionCode);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    @Override
    public String domain() {
        return "J";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            case "j1_gate_kill", "j1_gate_resume" -> {
                String enabled = "j1_gate_kill".equals(cmd.op()) ? "disabled" : "enabled";
                KillSwitchToggleRequest req = new KillSwitchToggleRequest(enabled, reason, operator);
                return killSwitchService.toggle(str(p, "gateKey"), idem, req);
            }
            case "j1_batch_kill" -> {
                EmergencyDisableRequest req = new EmergencyDisableRequest(strList(p, "keys"), reason, operator);
                return killSwitchService.emergencyDisable(idem, req);
            }
            case "j2_country_manage" -> {
                String countryCode = str(p, "countryCode");
                GeoCountryStatusRequest req = new GeoCountryStatusRequest(
                        str(p, "status"), firstText(str(p, "triggerBasis"), "其他"), reason, operator,
                        countryStatus(countryCode));
                return updateGeoCountry(countryCode, idem, req);
            }
            case "j2_emergency_block" -> {
                GeoEmergencyBlockRequest req = new GeoEmergencyBlockRequest(
                        strList(p, "countries"), firstText(str(p, "triggerBasis"), "其他"), reason, operator);
                return emergencyGeoBlock(idem, req);
            }
            case "j3_alert_config" -> {
                Map<String, Object> current = tamperAlertConfig();
                TamperAlertConfigRequest req = new TamperAlertConfigRequest(
                        intVal(p, "threshold"), boolVal(p, "feedK4"),
                        intValue(current.get("threshold")), boolValue(current.get("feedK4")), reason, operator);
                return updateTamperAlertConfig(idem, req);
            }
            case "j4_playbook_execute" -> {
                SopPlaybookRunRequest req = new SopPlaybookRunRequest(
                        boolVal(p, "emergency"), reason, operator,
                        str(p, "triggerBasis"), str(p, "triggerContext"), j4StepConfirmations(p));
                return executePlaybook(str(p, "code"), idem, req);
            }
            case "j4_playbook_rollback" -> {
                SopPlaybookRunRequest req = new SopPlaybookRunRequest(boolVal(p, "emergency"), reason, operator);
                return rollbackPlaybookExecution(str(p, "code"), str(p, "executionId"), idem, req);
            }
            default -> {
                return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
            }
        }
    }

    /** 从 replay params 取字符串,null 安全。 */
    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** 从 replay params 取 List<String>,null 安全(支持 List 与逗号分隔字符串)。 */
    private static java.util.List<String> strList(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) {
            return java.util.List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(StringUtils::hasText)
                    .toList();
        }
        String raw = String.valueOf(v).trim();
        if (raw.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /** 从 replay params 取 Boolean,null 安全。 */
    private static Boolean boolVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return null;
    }

    /** 从 replay params 取 Integer,null 安全(缺失返回 null,由 DTO 默认逻辑兜底)。 */
    private static Integer intVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static final class J4RollbackRejectedException extends RuntimeException {
        private final ApiResult<Map<String, Object>> rejection;
        @SuppressWarnings("ArchitectureConfigField")
        private final boolean claimConflict;

        private J4RollbackRejectedException(
                ApiResult<Map<String, Object>> rejection,
                boolean claimConflict) {
            super(rejection == null ? "J4_ROLLBACK_REJECTED" : rejection.getMessage());
            this.rejection = rejection;
            this.claimConflict = claimConflict;
        }

        private ApiResult<Map<String, Object>> rejection() {
            return rejection;
        }

        private boolean claimConflict() {
            return claimConflict;
        }
    }

    private static List<SopStepConfirmationRequest> j4StepConfirmations(Map<String, Object> params) {
        Object value = params.get("stepConfirmations");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<SopStepConfirmationRequest> confirmations = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                return List.of();
            }
            Object step = row.get("step");
            Integer stepNumber;
            if (step instanceof Number number) {
                stepNumber = number.intValue();
            } else {
                try {
                    stepNumber = Integer.parseInt(String.valueOf(step));
                } catch (RuntimeException invalid) {
                    return List.of();
                }
            }
            confirmations.add(new SopStepConfirmationRequest(
                    stepNumber,
                    row.get("domain") == null ? null : String.valueOf(row.get("domain")).trim(),
                    row.get("ref") == null ? null : String.valueOf(row.get("ref")).trim(),
                    Boolean.TRUE.equals(row.get("confirmed"))
                            || "true".equalsIgnoreCase(String.valueOf(row.get("confirmed")))));
        }
        return confirmations;
    }

    private record PlaybookSeed(String code, String name, String scene, boolean emergency, String sla, String state,
                                 String owner, String lastDrill, List<Map<String, Object>> seq,
                                 String notifyCampaignNo, String notifyTemplate, String rollback,
                                 boolean drillRequired, boolean draft, String version, String summary, String lastExecution) {
    }
}
