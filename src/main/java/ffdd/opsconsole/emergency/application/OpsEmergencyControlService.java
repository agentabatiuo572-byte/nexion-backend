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
import ffdd.opsconsole.emergency.dto.GeoEdgeJudgeRequest;
import ffdd.opsconsole.emergency.dto.GeoEmergencyBlockRequest;
import ffdd.opsconsole.emergency.dto.GeoEndpointCountriesRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookCreateRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
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
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsEmergencyControlService implements ffdd.opsconsole.platform.domain.AuditReplayable {
    private static final Pattern ISO_COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };
    private static final String GROUP_GEO_BLOCK = "admin_geo_block";
    private static final String GROUP_TAMPER = "admin_tamper";
    private static final String GROUP_SOP = "admin_sop";
    private static final String GEO_J4_BLOCK_REQUIRED = "emergency.geo.j4.block.required";
    private static final String GEO_EDGE_SOURCE = "emergency.geo.edgeJudgeSource";
    private static final String TAMPER_THRESHOLD = "emergency.tamper.alert.threshold";
    private static final String TAMPER_FEED_K4 = "emergency.tamper.alert.feedK4";
    private static final int SOP_EXECUTION_HISTORY_MAX_ROWS = 20;
    private static final int SOP_EXECUTION_HISTORY_MAX_BYTES = 60_000;
    private static final int SOP_EXECUTION_SEQUENCE_MAX_STEPS = 40;
    private static final int SOP_EXECUTION_TEXT_MAX_CHARS = 500;

    private final PlatformConfigFacade configFacade;
    private final ContentNotificationDispatchFacade notificationDispatchFacade;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final EmergencyControlRepository emergencyRepository;
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper;
    private final ffdd.opsconsole.emergency.application.OpsKillSwitchService killSwitchService;

    public ApiResult<Map<String, Object>> geoBlockOverview() {
        List<Map<String, Object>> countries = countryViews();
        List<Map<String, Object>> blocked = countries.stream()
                .filter(row -> "blocked".equals(row.get("status")))
                .toList();
        List<Map<String, Object>> limited = countries.stream()
                .filter(row -> "limited".equals(row.get("status")))
                .toList();
        List<Map<String, Object>> hits = geoHits();
        int totalHits = hits.stream().mapToInt(row -> intValue(row.get("count"))).sum();
        Map<String, Object> response = map(
                "domain", "J2",
                "blocked", blocked,
                "limited", limited,
                "countries", countries,
                "endpoints", geoEndpoints(),
                "hits", hits,
                "edge", geoEdge(),
                "stats", map(
                        "blockedCount", blocked.size(),
                        "limitedCount", limited.size(),
                        "totalHits", totalHits,
                        "health", activeValue("ops.J.emergency.health").orElse(""),
                        "confirmSlaMins", activeValue("ops.J.emergency.confirmSlaMins").orElse("")),
                "sources", List.of("nx_emergency_geo_country_policy", "nx_emergency_geo_endpoint_policy", "nx_emergency_geo_block_event", "nx_emergency_control_setting"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateGeoCountry(String countryCode, String idempotencyKey, GeoCountryStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String cc = normalizeCountry(countryCode);
        String status = normalizeGeoStatus(request.status());
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("J", "country", cc) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        writeCountryStatus(cc, status, request.reason().trim(), request.operator());
        audit("J2_GEO_COUNTRY_STATUS_CHANGED", "GEO_COUNTRY", cc, request.operator(), "HIGH", map(
                "country", cc,
                "status", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = geoBlockOverview().getData();
        response.put("updated", countryView(cc));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateGeoEndpoint(String endpointKey, String idempotencyKey, GeoEndpointCountriesRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Map<String, Object> endpoint = endpointCatalog(endpointKey);
        List<String> countries = normalizeCountries(request.countries());
        emergencyRepository.replaceGeoEndpointPolicies(
                stringValue(endpoint.get("endpointKey"), ""),
                stringValue(endpoint.get("endpointPath"), ""),
                stringValue(endpoint.get("label"), ""),
                stringValue(endpoint.get("biz"), ""),
                stringValue(endpoint.get("domain"), ""),
                countries,
                "explicit",
                request.reason().trim(),
                request.operator());
        audit("J2_GEO_ENDPOINT_COUNTRIES_CHANGED", "GEO_ENDPOINT", stringValue(endpoint.get("endpointKey"), ""), request.operator(), "HIGH", map(
                "endpointKey", endpoint.get("endpointKey"),
                "endpoint", endpoint.get("endpointPath"),
                "countries", countries,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = geoBlockOverview().getData();
        int hits = emergencyRepository.geoEndpointHits().getOrDefault(stringValue(endpoint.get("endpointKey"), ""), 0);
        Map<String, Object> updated = endpointView(endpoint, countries, "explicit", request.reason().trim(), hits);
        response.put("updated", updated);
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateGeoEdgeJudge(String idempotencyKey, GeoEdgeJudgeRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(request.source())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EDGE_JUDGE_SOURCE_REQUIRED");
        }
        String source = request.source().trim();
        emergencyRepository.upsertSetting(GEO_EDGE_SOURCE, source, "STRING", GROUP_GEO_BLOCK, request.reason().trim(), request.operator());
        audit("J2_GEO_EDGE_JUDGE_SOURCE_CHANGED", "GEO_EDGE", "edgeJudgeSource", request.operator(), "HIGH", map(
                "source", source,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return geoBlockOverview();
    }

    public ApiResult<Map<String, Object>> emergencyGeoBlock(String idempotencyKey, GeoEmergencyBlockRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        List<String> countries = normalizeCountries(request.countries());
        if (countries.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EMERGENCY_BLOCK_COUNTRIES_REQUIRED");
        }
        for (String country : countries) {
            if (!A2ReplayContext.isReplaying()
                    && lockMapper.countActiveByTarget("J", "country", country) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
            writeCountryStatus(country, "blocked", request.reason().trim(), request.operator());
        }
        audit("J2_GEO_EMERGENCY_BLOCK_CREATED", "GEO_COUNTRY_BATCH", String.join(",", countries), request.operator(), "CRITICAL", map(
                "countries", countries,
                "emergency", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = geoBlockOverview().getData();
        response.put("updated", map("countries", countries, "status", "blocked", "emergency", true));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> tamperOverview() {
        return tamperOverview(1, 5);
    }

    public ApiResult<Map<String, Object>> tamperOverview(int accountPage, int accountPageSize) {
        List<Map<String, Object>> accounts = tamperAccounts();
        int pageSize = accountPageSize <= 0 ? 5 : Math.min(accountPageSize, 50);
        int totalAccounts = accounts.size();
        int pages = Math.max(1, (int) Math.ceil(totalAccounts / (double) pageSize));
        int page = Math.max(1, Math.min(accountPage, pages));
        int from = Math.min(totalAccounts, (page - 1) * pageSize);
        int to = Math.min(totalAccounts, from + pageSize);
        List<Map<String, Object>> pagedAccounts = accounts.subList(from, to);
        int today = tamperPaths().stream().mapToInt(row -> intValue(row.get("count"))).sum();
        int sevenDay = mapListValues((Map<?, ?>) tamperTrend().get("7d"), "points")
                .stream()
                .mapToInt(this::intValue)
                .sum();
        Map<String, Object> response = map(
                "domain", "J3",
                "stats", map(
                        "todayBlocked", today,
                        "highFrequencyAccounts", totalAccounts,
                        "sevenDayBlocked", sevenDay,
                        "lossUsd", null),
                "trend", tamperTrend(),
                "paths", tamperPaths(),
                "accounts", pagedAccounts,
                "accountPage", map(
                        "page", page,
                        "pageSize", pageSize,
                        "total", totalAccounts,
                        "pages", pages,
                        "hasPrev", page > 1,
                        "hasNext", page < pages),
                "alertConfig", tamperAlertConfig(),
                "sources", List.of("nx_emergency_tamper_event", "nx_emergency_control_setting"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateTamperAlertConfig(String idempotencyKey, TamperAlertConfigRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        int threshold = request.threshold() == null ? 10 : request.threshold();
        if (threshold < 1 || threshold > 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TAMPER_THRESHOLD_RANGE_1_100");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("J", "alert_config", "default") > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        boolean feedK4 = request.feedK4() == null || request.feedK4();
        emergencyRepository.upsertSetting(TAMPER_THRESHOLD, String.valueOf(threshold), "NUMBER", GROUP_TAMPER, request.reason().trim(), request.operator());
        emergencyRepository.upsertSetting(TAMPER_FEED_K4, String.valueOf(feedK4), "BOOLEAN", GROUP_TAMPER, request.reason().trim(), request.operator());
        audit("J3_TAMPER_ALERT_CONFIG_CHANGED", "TAMPER_ALERT_CONFIG", "default", request.operator(), "HIGH", map(
                "threshold", threshold,
                "feedK4", feedK4,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return tamperOverview();
    }

    public ApiResult<Map<String, Object>> createTamperReport(String idempotencyKey, TamperReportRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String reportId = "J3-RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String window = StringUtils.hasText(request.window()) ? request.window().trim() : "24h";
        List<Map<String, Object>> paths = tamperPaths();
        List<Map<String, Object>> accounts = tamperAccounts();
        Map<String, Object> trend = emergencyRepository.tamperTrend(LocalDateTime.now());
        Map<String, Object> payload = map(
                "trend", trend,
                "paths", paths,
                "accounts", accounts,
                "eventCount", paths.stream().mapToInt(row -> intValue(row.get("count"))).sum(),
                "accountCount", accounts.size(),
                "sources", List.of("nx_emergency_tamper_event"));
        emergencyRepository.createTamperReport(
                reportId,
                window,
                true,
                "READY",
                payload,
                request.operator(),
                request.reason().trim());
        audit("J3_TAMPER_REPORT_EXPORTED", "TAMPER_REPORT", reportId, request.operator(), "MEDIUM", map(
                "reportId", reportId,
                "window", window,
                "source", "nx_emergency_tamper_report",
                "masked", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(map(
                "reportId", reportId,
                "window", window,
                "masked", true,
                "status", "READY",
                "eventCount", payload.get("eventCount"),
                "accountCount", payload.get("accountCount"),
                "source", "nx_emergency_tamper_report"));
    }

    public ApiResult<Map<String, Object>> sopOverview() {
        List<Map<String, Object>> playbooks = new ArrayList<>(playbookSeeds().stream().map(this::playbookView).toList());
        List<Map<String, Object>> executions = executionSeeds();
        long ready = playbooks.stream().filter(row -> "active".equals(row.get("state"))).count();
        long emergency = playbooks.stream().filter(row -> Boolean.TRUE.equals(row.get("emergency"))).count();
        Map<String, Object> response = map(
                "domain", "J4",
                "stats", map(
                        "playbookCount", playbooks.size(),
                        "readyCount", ready,
                        "todoCount", playbooks.size() - ready,
                        "emergencyCount", emergency,
                        "liveExec90d", executions.stream().filter(row -> !String.valueOf(row.get("name")).contains("(演练)")).count(),
                        "drill90d", executions.stream().filter(row -> String.valueOf(row.get("name")).contains("(演练)")).count()),
                "sla", map(
                        "confirmSlaMins", activeValue("ops.J.emergency.confirmSlaMins")
                                .orElse(""),
                        "escalateMaxMins", activeValue("ops.J.emergency.escalateMaxMins")
                                .orElse(""),
                        "escalateMaxRounds", activeValue("ops.J.emergency.escalateMaxRounds")
                                .orElse("")),
                "scenes", List.of("全部", "监管点名", "资金异常", "数据泄露", "舆情挤兑", "技术故障"),
                "actionOptions", defaultActionOptions(),
                "rollbackOptions", defaultRollbackOptions(),
                "h1Rhythm", GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy).summary(),
                "playbooks", playbooks,
                "executions", executions,
                "sources", List.of("nx_emergency_sop_playbook", "nx_emergency_sop_action", "nx_emergency_sop_execution", "nx_notification_campaign", "nx_notification", "nx_audit_log", "H1 growth rhythm facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createPlaybook(String idempotencyKey, SopPlaybookCreateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        emergencyRepository.ensureTables();
        if (!StringUtils.hasText(request.name())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PLAYBOOK_NAME_REQUIRED");
        }
        List<Map<String, Object>> rows = new ArrayList<>(emergencyRepository.playbooks());
        Map<String, Object> row = playbookDraftRow(nextDraftCode(rows), request);
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
                request.operator());
        audit("J4_SOP_PLAYBOOK_CREATED", "SOP_PLAYBOOK", String.valueOf(row.get("code")), request.operator(), "HIGH", map(
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

    public ApiResult<Map<String, Object>> updatePlaybook(String code, String idempotencyKey, SopPlaybookUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        emergencyRepository.ensureTables();
        PlaybookSeed seed = playbookSeed(code);
        String summary = StringUtils.hasText(request.summary()) ? request.summary().trim() : seed.seq().size() + " steps";
        Map<String, Object> updatedRow = updatePlaybookRow(seed.code(), request);
        audit("J4_SOP_PLAYBOOK_EDITED", "SOP_PLAYBOOK", seed.code(), request.operator(), "HIGH", map(
                "code", seed.code(),
                "summary", summary,
                "notifyCampaignNo", updatedRow.get("notifyCampaignNo"),
                "notifyTemplate", updatedRow.get("notifyTemplate"),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return sopOverview();
    }

    public ApiResult<Map<String, Object>> drillPlaybook(String code, String idempotencyKey, SopPlaybookRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        PlaybookSeed seed = playbookSeed(code);
        String now = LocalDateTime.now().format(TS);
        emergencyRepository.markPlaybookDrilled(seed.code(), LocalDateTime.now(), request.operator());
        audit("J4_SOP_PLAYBOOK_DRILL_STARTED", "SOP_PLAYBOOK", seed.code(), request.operator(), "MEDIUM", map(
                "code", seed.code(),
                "sandbox", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return sopOverview();
    }

    @Transactional
    public ApiResult<Map<String, Object>> executePlaybook(String code, String idempotencyKey, SopPlaybookRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        PlaybookSeed seed = playbookSeed(code);
        boolean emergency = request.emergency() != null && request.emergency();
        if (emergency && !seed.emergency()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PLAYBOOK_NOT_EMERGENCY_TRACK");
        }
        ApiResult<Map<String, Object>> validation = validateExecutablePlaybook(seed);
        if (validation != null) {
            return validation;
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("J", "playbook", seed.code()) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        Optional<Map<String, Object>> existingExecution = emergencyRepository.executionByIdempotencyKey(seed.code(), idempotencyKey.trim());
        if (existingExecution.isPresent()) {
            String existingExecId = stringValue(existingExecution.get().get("executionId"), "");
            audit("J4_SOP_PLAYBOOK_EXECUTED", "SOP_PLAYBOOK_EXECUTION", existingExecId, request.operator(), emergency ? "CRITICAL" : "HIGH", map(
                    "code", seed.code(),
                    "emergency", emergency,
                    "idempotentReplay", true,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = sopOverview().getData();
            response.put("updated", map(
                    "executionId", existingExecId,
                    "code", seed.code(),
                    "idempotentReplay", true));
            return ApiResult.ok(response);
        }
        String execId = seed.code() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        List<Map<String, Object>> domainActions = executeDomainActions(seed, execId, request);
        Map<String, Object> notificationDispatch = notificationDispatch(seed, execId, request);
        if (notificationDispatch == null) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_NOTIFY_CAMPAIGN_NOT_FOUND");
        }
        appendExecution(seed, execId, emergency, idempotencyKey.trim(), request, notificationDispatch, domainActions);
        audit("J4_SOP_PLAYBOOK_EXECUTED", "SOP_PLAYBOOK_EXECUTION", execId, request.operator(), emergency ? "CRITICAL" : "HIGH", map(
                "code", seed.code(),
                "emergency", emergency,
                "steps", seed.seq(),
                "domainActions", domainActions,
                "notificationDispatch", notificationDispatch,
                "rollback", seed.rollback(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
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

    @Transactional
    public ApiResult<Map<String, Object>> rollbackPlaybookExecution(
            String code,
            String executionId,
            String idempotencyKey,
            SopPlaybookRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String requestedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String execId = stringValue(executionId, "").trim();
        if (!StringUtils.hasText(execId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_EXECUTION_ID_REQUIRED");
        }
        List<Map<String, Object>> rows = new ArrayList<>(executionSeeds());
        Optional<Map<String, Object>> found = rows.stream()
                .filter(row -> execId.equals(stringValue(row.get("executionId"), ""))
                        && (!StringUtils.hasText(requestedCode)
                        || requestedCode.equals(stringValue(row.get("code"), "").toUpperCase(Locale.ROOT))))
                .findFirst();
        if (found.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_EXECUTION_NOT_FOUND");
        }
        Map<String, Object> execution = found.get();
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("J", "playbook_execution", execId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String playbookCode = requestedCode;
        if (!StringUtils.hasText(playbookCode)) {
            playbookCode = stringValue(execution.get("code"), "").toUpperCase(Locale.ROOT);
        }
        PlaybookSeed seed = findPlaybookSeed(playbookCode).orElse(null);
        String effectiveCode = seed == null ? playbookCode : seed.code();
        if ("ROLLED_BACK".equals(stringValue(execution.get("rollbackStatus"), ""))) {
            Map<String, Object> response = sopOverview().getData();
            response.put("updated", map("executionId", execId, "code", effectiveCode, "idempotentReplay", true, "rollbackStatus", "ROLLED_BACK"));
            return ApiResult.ok(response);
        }
        List<Map<String, Object>> rollbackWrites = rollbackDomainActions(execId, mapList(execution.get("domainActions")), request);
        execution.put("rollbackStatus", "ROLLED_BACK");
        execution.put("rollbackAt", LocalDateTime.now().format(TS));
        execution.put("rollbackReason", request.reason().trim());
        execution.put("rollbackActions", rollbackWrites);
        emergencyRepository.markExecutionRolledBack(execId, LocalDateTime.now(), request.reason().trim(), rollbackWrites);
        audit("J4_SOP_PLAYBOOK_ROLLED_BACK", "SOP_PLAYBOOK_EXECUTION", execId, request.operator(), "HIGH", map(
                "code", effectiveCode,
                "playbookSnapshotMissing", seed == null,
                "domainActions", rollbackWrites,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = sopOverview().getData();
        response.put("updated", map(
                "executionId", execId,
                "code", effectiveCode,
                "playbookSnapshotMissing", seed == null,
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
        if (containsAny(normalizedAction, "withdraw", "提现")) {
            addDomainWrite(writes, "J1", stepIndex, action, "killswitch.withdraw", "disabled",
                    "STRING", "admin_killswitch", "J4 withdraw kill switch | execId=" + execId, request);
        }
        if (containsAny(normalizedAction, "exchange", "兑换")) {
            addDomainWrite(writes, "J1", stepIndex, action, "killswitch.exchange", "disabled",
                    "STRING", "admin_killswitch", "J4 exchange kill switch | execId=" + execId, request);
        }
        if (containsAny(normalizedAction, "genesis", "二级市场")) {
            addDomainWrite(writes, "J1", stepIndex, action, "killswitch.genesis", "disabled",
                    "STRING", "admin_killswitch", "J4 Genesis kill switch | execId=" + execId, request);
        }
        if (containsAny(normalizedAction, "maintenance", "维护", "隔离")) {
            addDomainWrite(writes, "J1", stepIndex, action, "killswitch.maintenance", "enabled",
                    "STRING", "admin_killswitch", "J4 maintenance mode | execId=" + execId, request);
        }
        if (containsAny(normalizedAction, "restore", "恢复")) {
            addDomainWrite(writes, "J1", stepIndex, action, "emergency.j4.restore.pending_b1",
                    String.valueOf(containsAny(normalizedAction, "b1", "覆盖")), "BOOLEAN", GROUP_SOP,
                    "J4 restore requires B1 check | execId=" + execId, request);
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
                    "BOOLEAN", "content", "J4 I4 disclosure gate required | execId=" + execId, request);
        }
        addDomainWrite(writes, sourceDomain, stepIndex, action, "disclosure.gate.lastExecution", execId,
                "STRING", "content", "J4 I4 disclosure gate execution link", request);
    }

    private void addDomainWrite(List<Map<String, Object>> writes, String domain, int stepIndex, String action,
                                 String configKey, String value, String valueType, String group, String remark,
                                 SopPlaybookRunRequest request) {
        if (appliesToEmergencyControlSetting(configKey)) {
            emergencyRepository.upsertSetting(configKey, value, valueType, group, remark, stringValue(request.operator(), "system"));
        }
        writes.add(map(
                "domain", domain,
                "step", stepIndex,
                "action", action,
                "configKey", configKey,
                "value", value,
                "valueType", valueType,
                "group", group,
                "operator", stringValue(request.operator(), "system")));
    }

    private boolean appliesToEmergencyControlSetting(String configKey) {
        return StringUtils.hasText(configKey)
                && (configKey.startsWith("killswitch.") || configKey.startsWith("emergency.killswitch."));
    }

    private List<Map<String, Object>> rollbackDomainActions(
            String execId,
            List<Map<String, Object>> domainActions,
            SopPlaybookRunRequest request) {
        List<Map<String, Object>> writes = new ArrayList<>();
        for (Map<String, Object> action : domainActions) {
            String configKey = stringValue(action.get("configKey"), "");
            Optional<String> restoreValue = rollbackValueFor(configKey, execId);
            if (restoreValue.isEmpty()) {
                continue;
            }
            String valueType = stringValue(action.get("valueType"), "STRING");
            String group = stringValue(action.get("group"), GROUP_SOP);
            if (appliesToEmergencyControlSetting(configKey)) {
                emergencyRepository.upsertSetting(
                        configKey,
                        restoreValue.get(),
                        valueType,
                        group,
                        "J4 rollback | execId=" + execId,
                        stringValue(request.operator(), "system"));
            }
            writes.add(map(
                    "domain", stringValue(action.get("domain"), ""),
                    "step", action.get("step"),
                    "configKey", configKey,
                    "value", restoreValue.get(),
                    "valueType", valueType,
                    "group", group,
                    "operator", stringValue(request.operator(), "system")));
        }
        writes.add(map(
                "domain", "I3",
                "configKey", "notification.j4.rollback.requested",
                "value", execId,
                "valueType", "STRING",
                "group", "content",
                "operator", stringValue(request.operator(), "system")));
        return writes;
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
            List<Map<String, Object>> domainActions) {
        Map<String, Object> row = execution(
                LocalDateTime.now().format(TS),
                seed.code(),
                seed.name(),
                request.reason().trim(),
                emergency ? "emergency" : "regular",
                seed.seq().stream().map(ignored -> "done").toList(),
                stringValue(request.operator(), "system"),
                seed.owner());
        row.put("executionId", execId);
        row.put("notificationDispatch", notificationDispatch);
        row.put("domainActions", domainActions);
        row.put("rollback", seed.rollback());
        row.put("idempotencyKey", idempotencyKey);
        emergencyRepository.createExecution(row);
    }

    private ApiResult<Map<String, Object>> validateExecutablePlaybook(PlaybookSeed seed) {
        if (requiresI3Dispatch(seed) && !StringUtils.hasText(seed.notifyCampaignNo())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_NOTIFY_CAMPAIGN_NO_REQUIRED");
        }
        if (seed.seq().size() > SOP_EXECUTION_SEQUENCE_MAX_STEPS) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_SEQUENCE_TOO_LONG");
        }
        boolean actionTooLong = seed.seq().stream()
                .map(step -> stringValue(step.get("action"), ""))
                .anyMatch(action -> action.length() > SOP_EXECUTION_TEXT_MAX_CHARS);
        if (actionTooLong) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "J4_ACTION_TEXT_TOO_LONG");
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
        return null;
    }

    private List<Map<String, Object>> countryViews() {
        return emergencyRepository.geoCountryPolicies().stream()
                .filter(row -> !"allowed".equals(row.get("status")))
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("status"))))
                .toList();
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
        return map("cc", cc, "name", name, "status", status, "reason", reason);
    }

    private void writeCountryStatus(String countryCode, String status, String reason, String operator) {
        String cc = normalizeCountry(countryCode);
        String name = emergencyRepository.geoCountryPolicies().stream()
                .filter(row -> cc.equals(row.get("cc")))
                .map(row -> stringValue(row.get("name"), ""))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(cc);
        emergencyRepository.upsertGeoCountryPolicy(cc, name, status, reason, operator);
    }

    private String normalizeCountry(String raw) {
        String country = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRY.matcher(country).matches()) {
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
        return new ArrayList<>(countries);
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
                            .orElse("pending");
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
        return map(
                "source", emergencyRepository.settingValue(GEO_EDGE_SOURCE).orElse(""),
                "metrics", emergencyRepository.geoEdgeMetrics());
    }

    private List<Map<String, Object>> geoHits() {
        return emergencyRepository.geoHits();
    }

    private Map<String, Object> tamperAlertConfig() {
        int threshold = emergencyRepository.settingValue(TAMPER_THRESHOLD)
                .map(Integer::parseInt)
                .orElse(0);
        boolean feedK4 = emergencyRepository.settingValue(TAMPER_FEED_K4)
                .map(Boolean::parseBoolean)
                .orElse(false);
        return map("threshold", threshold, "label", threshold + " 次 / 24h", "feedK4", feedK4);
    }

    private Map<String, Object> tamperTrend() {
        return emergencyRepository.tamperTrend(LocalDateTime.now());
    }

    private List<Map<String, Object>> tamperPaths() {
        return emergencyRepository.tamperPaths();
    }

    private List<Map<String, Object>> tamperAccounts() {
        return emergencyRepository.tamperAccounts().stream()
                .map(this::tamperAccountView)
                .toList();
    }

    private Map<String, Object> tamperAccountView(Map<String, Object> row) {
        String userCode = firstText(row.get("userCode"), row.get("userNo"), row.get("uid"));
        userCode = toUserCode(userCode);
        return tamperAccount(
                userCode,
                intValue(row.get("count")),
                stringValue(row.get("k4"), ""),
                stringValue(row.get("last"), ""),
                stringList(row.get("paths")),
                stringValue(row.get("cluster"), ""));
    }

    private Map<String, Object> tamperAccount(String userCode, int count, String k4, String last, List<String> paths, String cluster) {
        String normalizedUserCode = toUserCode(userCode);
        return map("userCode", normalizedUserCode, "userNo", normalizedUserCode, "count", count, "k4", k4, "last", last, "paths", paths, "cluster", cluster);
    }

    private Map<String, Object> playbookView(PlaybookSeed seed) {
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
                "customSummary", seed.summary(),
                "lastExecution", seed.lastExecution());
    }

    private Map<String, Object> playbookDraftRow(String code, SopPlaybookCreateRequest request) {
        return map(
                "code", code,
                "name", request.name().trim(),
                "scene", stringValue(request.scene(), "监管点名"),
                "emergency", request.emergencyTrack() != null && request.emergencyTrack(),
                "sla", stringValue(request.sla(), "15 分钟"),
                "state", request.drillRequired() == null || request.drillRequired() ? "todo" : "active",
                "owner", stringValue(request.owner(), "风控"),
                "lastDrill", "未演练",
                "sequence", parseActionSequence(request.actionSeq()),
                "notifyCampaignNo", stringValue(request.notifyCampaignNo(), ""),
                "notifyTemplate", stringValue(request.notifyTemplate(), ""),
                "rollback", stringValue(request.rollback(), ""),
                "drillRequired", request.drillRequired() == null || request.drillRequired(),
                "draft", true);
    }

    private Map<String, Object> updatePlaybookRow(String code, SopPlaybookUpdateRequest request) {
        List<Map<String, Object>> sequence = StringUtils.hasText(request.actionSeq()) ? parseActionSequence(request.actionSeq()) : null;
        emergencyRepository.updatePlaybook(
                code,
                request.name(),
                request.scene(),
                request.emergencyTrack(),
                request.sla(),
                null,
                request.owner(),
                request.notifyCampaignNo(),
                request.notifyTemplate(),
                request.rollback(),
                request.drillRequired(),
                request.summary(),
                sequence,
                request.operator());
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
                "sourceLabel", switch (source) {
                    case "explicit" -> "单独设定";
                    case "derived" -> "继承全局";
                    default -> "待设置";
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
                "draft", seed.draft());
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
                actionOption("J1", "熔断提现通道", "withdraw", true, "关闭提现写入入口,用于挤兑或对账缺口止血"),
                actionOption("J1", "熔断 Genesis 交易", "genesis", true, "暂停 Genesis 交易或二级市场入口"),
                actionOption("J1", "进入维护模式", "maintenance", true, "全站技术故障时切换维护入口"),
                actionOption("J2", "封锁高风险辖区", "geo-block", true, "按 J2 国家/地区黑名单阻断访问或交易"),
                actionOption("D2", "提现限流 50%", "withdraw-rate-limit", true, "降低提现出金速度,等待 B1 容量核验"),
                actionOption("D2", "按 B1 容量分批放行", "withdraw-batch-release", true, "对已审核提现按资金覆盖率批次放行"),
                actionOption("B1", "核验备付金覆盖率", "coverage-ratio", false, "恢复或放大流出前的双账本覆盖率检查"),
                actionOption("C2", "冻结涉事账户", "account-freeze", true, "冻结被命中账户或账户簇,保留取证链"),
                actionOption("C2", "存量账户转只读", "account-readonly", true, "地域合规收紧时将存量账户切换为只读"),
                actionOption("K1", "账户簇建档调查", "cluster-investigation", true, "把命中账户簇送入 K1 多账户调查"),
                actionOption("I3", "发送通知模板", "campaign-notify", false, "使用 I3 Campaign 模板通知用户、法务、财务或超管"),
                actionOption("I4", "发布风险披露/公告", "compliance-disclosure", true, "发布或切换合规披露、风险提示与公告内容"));
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
                rollbackOption("root-cause-standard", "常规根因恢复", "通用", "MEDIUM", "根因消除 + 执行门槛操作确认后逐步恢复;恢复恒走常规轨"),
                rollbackOption("withdraw-rate-limit", "提现限流恢复", "资金异常", "HIGH", "B1 覆盖率恢复绿线 + D2 待处理队列清零后,按批次恢复提现限流"),
                rollbackOption("geo-block-release", "地域封锁恢复", "监管点名", "HIGH", "合规名单确认解除 + J2 国家状态回到 limited/allowed + 保留审计快照"),
                rollbackOption("account-freeze-release", "账户冻结恢复", "资金异常", "HIGH", "C2 取证完成 + 风控复核通过后,批量解除冻结或降为只读"),
                rollbackOption("content-disclosure-correction", "披露公告更正", "数据泄露", "MEDIUM", "I4 发布更正公告 + 保留旧版本引用 + 内容审核通过后切换版本"),
                rollbackOption("maintenance-gray-release", "维护模式灰度恢复", "技术故障", "HIGH", "核心链路健康检查通过 + 灰度流量恢复 + J1 维护模式关闭"),
                rollbackOption("campaign-correction", "通知误发修正", "舆情挤兑", "MEDIUM", "I3 停止后续触达 + 发布更正通知 + 导出影响用户名单"),
                rollbackOption("drill-downgrade", "实战降级为演练", "通用", "LOW", "应急轨执行撤销为演练记录,保留 A2 审计并关闭生产动作"));
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
                    String action = parts.length > 1 ? line.substring(parts[0].length() + 1).trim() : line;
                    if (!domain.matches("^[A-Z]\\d?$")) {
                        domain = "J4";
                        action = line;
                    }
                    return map("domain", domain, "action", action, "approve", !"I3".equals(domain), "ref", "");
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
        if (value instanceof String string) {
            return Boolean.parseBoolean(string.trim());
        }
        return false;
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
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel(riskLevel)
                .detail(detail)
                .build());
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
                GeoCountryStatusRequest req = new GeoCountryStatusRequest(str(p, "status"), reason, operator);
                return updateGeoCountry(str(p, "countryCode"), idem, req);
            }
            case "j2_emergency_block" -> {
                GeoEmergencyBlockRequest req = new GeoEmergencyBlockRequest(strList(p, "countries"), reason, operator);
                return emergencyGeoBlock(idem, req);
            }
            case "j3_alert_config" -> {
                TamperAlertConfigRequest req = new TamperAlertConfigRequest(intVal(p, "threshold"), boolVal(p, "feedK4"), reason, operator);
                return updateTamperAlertConfig(idem, req);
            }
            case "j4_playbook_execute" -> {
                SopPlaybookRunRequest req = new SopPlaybookRunRequest(boolVal(p, "emergency"), reason, operator);
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

    private record PlaybookSeed(String code, String name, String scene, boolean emergency, String sla, String state,
                                String owner, String lastDrill, List<Map<String, Object>> seq,
                                String notifyCampaignNo, String notifyTemplate, String rollback,
                                boolean drillRequired, boolean draft, String summary, String lastExecution) {
    }
}
