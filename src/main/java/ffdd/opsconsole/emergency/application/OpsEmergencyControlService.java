package ffdd.opsconsole.emergency.application;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.ContentNotificationDispatchFacade;
import ffdd.opsconsole.content.facade.NotificationEmergencyDispatchResult;
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
public class OpsEmergencyControlService {
    private static final Pattern ISO_COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String GROUP_GEO_BLOCK = "admin_geo_block";
    private static final String GROUP_TAMPER = "admin_tamper";
    private static final String GROUP_SOP = "admin_sop";
    private static final String GEO_CUSTOM_COUNTRIES = "emergency.geo.customCountries";
    private static final String GEO_J4_BLOCK_REQUIRED = "emergency.geo.j4.block.required";
    private static final String GEO_EDGE_SOURCE = "emergency.geo.edgeJudgeSource";
    private static final String GEO_EDGE_METRICS = "emergency.geo.edge.metrics";
    private static final String GEO_HITS = "emergency.geo.hits";
    private static final String TAMPER_THRESHOLD = "emergency.tamper.alert.threshold";
    private static final String TAMPER_FEED_K4 = "emergency.tamper.alert.feedK4";
    private static final String TAMPER_TREND = "emergency.tamper.trend";
    private static final String TAMPER_PATHS = "emergency.tamper.paths";
    private static final String TAMPER_ACCOUNTS = "emergency.tamper.accounts";
    private static final String SOP_DRAFT_NAMES = "emergency.sop.draftNames";
    private static final String SOP_PLAYBOOKS = "emergency.sop.playbooks";
    private static final String SOP_EXECUTIONS = "emergency.sop.executions";
    private static final String SOP_ACTION_OPTIONS = "emergency.sop.actionOptions";
    private static final String SOP_ROLLBACK_OPTIONS = "emergency.sop.rollbackOptions";

    private final PlatformConfigFacade configFacade;
    private final ContentNotificationDispatchFacade notificationDispatchFacade;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<Map<String, Object>> geoBlockOverview() {
        ensureSeedData();
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
                "sources", List.of("nx_config_item:emergency.geo.*", "server edge geo decision stream"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateGeoCountry(String countryCode, String idempotencyKey, GeoCountryStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String cc = normalizeCountry(countryCode);
        String status = normalizeGeoStatus(request.status());
        writeCountryStatus(cc, status);
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
        EndpointSeed endpoint = endpointSeed(endpointKey);
        List<String> countries = normalizeCountries(request.countries());
        configFacade.upsertAdminValue(endpointConfigKey(endpoint.key()), String.join(",", countries), "STRING", GROUP_GEO_BLOCK, request.reason().trim());
        configFacade.upsertAdminValue(endpointSourceConfigKey(endpoint.key()), "explicit", "STRING", GROUP_GEO_BLOCK, "J2 endpoint geo source");
        audit("J2_GEO_ENDPOINT_COUNTRIES_CHANGED", "GEO_ENDPOINT", endpoint.key(), request.operator(), "HIGH", map(
                "endpointKey", endpoint.key(),
                "endpoint", endpoint.endpoint(),
                "countries", countries,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = geoBlockOverview().getData();
        response.put("updated", endpointView(endpoint));
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
        configFacade.upsertAdminValue(GEO_EDGE_SOURCE, source, "STRING", GROUP_GEO_BLOCK, request.reason().trim());
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
            writeCountryStatus(country, "blocked");
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
        ensureSeedData();
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
                        "lossUsd", 0),
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
                "sources", List.of("server tamper intercept event stream", "nx_config_item:emergency.tamper.*"));
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
        boolean feedK4 = request.feedK4() == null || request.feedK4();
        configFacade.upsertAdminValue(TAMPER_THRESHOLD, String.valueOf(threshold), "NUMBER", GROUP_TAMPER, request.reason().trim());
        configFacade.upsertAdminValue(TAMPER_FEED_K4, String.valueOf(feedK4), "BOOLEAN", GROUP_TAMPER, request.reason().trim());
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
        audit("J3_TAMPER_REPORT_EXPORTED", "TAMPER_REPORT", reportId, request.operator(), "MEDIUM", map(
                "reportId", reportId,
                "window", window,
                "masked", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(map("reportId", reportId, "window", window, "masked", true, "status", "READY"));
    }

    public ApiResult<Map<String, Object>> sopOverview() {
        ensureSeedData();
        List<Map<String, Object>> playbooks = new ArrayList<>(playbookSeeds().stream().map(this::playbookView).toList());
        playbooks.addAll(draftPlaybooks(playbooks.stream().map(row -> String.valueOf(row.get("code"))).collect(Collectors.toSet())));
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
                "actionOptions", enumJsonList(SOP_ACTION_OPTIONS, defaultActionOptions()),
                "rollbackOptions", enumJsonList(SOP_ROLLBACK_OPTIONS, defaultRollbackOptions()),
                "h1Rhythm", GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy).summary(),
                "playbooks", playbooks,
                "executions", executions,
                "sources", List.of("nx_config_item:emergency.sop.*", "nx_notification_campaign", "nx_notification", "A2 emergency playbook audit", "H1 growth rhythm facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createPlaybook(String idempotencyKey, SopPlaybookCreateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ensureSeedData();
        if (!StringUtils.hasText(request.name())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PLAYBOOK_NAME_REQUIRED");
        }
        List<Map<String, Object>> rows = new ArrayList<>(jsonList(SOP_PLAYBOOKS, List.of()));
        Map<String, Object> row = playbookDraftRow(nextDraftCode(rows), request);
        rows.add(row);
        configFacade.upsertAdminValue(SOP_PLAYBOOKS, toJson(rows), "JSON", GROUP_SOP, request.reason().trim());
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
        ensureSeedData();
        PlaybookSeed seed = playbookSeed(code);
        String summary = StringUtils.hasText(request.summary()) ? request.summary().trim() : seed.seq().size() + " steps";
        Map<String, Object> updatedRow = updatePlaybookRow(seed.code(), request);
        configFacade.upsertAdminValue(playbookConfigKey(seed.code(), "summary"), summary, "STRING", GROUP_SOP, request.reason().trim());
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
        configFacade.upsertAdminValue(playbookConfigKey(seed.code(), "drillAt"), now, "STRING", GROUP_SOP, request.reason().trim());
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
        String idemConfigKey = playbookConfigKey(seed.code(), "idem." + sanitizeConfigPart(idempotencyKey));
        Optional<String> existingExecId = activeValue(idemConfigKey).filter(StringUtils::hasText);
        if (existingExecId.isPresent()) {
            audit("J4_SOP_PLAYBOOK_EXECUTED", "SOP_PLAYBOOK_EXECUTION", existingExecId.get(), request.operator(), emergency ? "CRITICAL" : "HIGH", map(
                    "code", seed.code(),
                    "emergency", emergency,
                    "idempotentReplay", true,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = sopOverview().getData();
            response.put("updated", map(
                    "executionId", existingExecId.get(),
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
        configFacade.upsertAdminValue(playbookConfigKey(seed.code(), "lastExecution"), execId, "STRING", GROUP_SOP, request.reason().trim());
        configFacade.upsertAdminValue(idemConfigKey, execId, "STRING", GROUP_SOP, "J4 SOP idempotency replay marker");
        appendExecution(seed, execId, emergency, request, notificationDispatch, domainActions);
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
        configFacade.upsertAdminValue(SOP_EXECUTIONS, toJson(rows), "JSON", GROUP_SOP, request.reason().trim());
        configFacade.upsertAdminValue(playbookConfigKey(effectiveCode, "rollback." + sanitizeConfigPart(execId)), "ROLLED_BACK", "STRING", GROUP_SOP, request.reason().trim());
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
        configFacade.upsertAdminValue(configKey, value, valueType, group, remark);
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
            configFacade.upsertAdminValue(
                    configKey,
                    restoreValue.get(),
                    valueType,
                    group,
                    "J4 rollback | execId=" + execId);
            writes.add(map(
                    "domain", stringValue(action.get("domain"), ""),
                    "step", action.get("step"),
                    "configKey", configKey,
                    "value", restoreValue.get(),
                    "valueType", valueType,
                    "group", group,
                    "operator", stringValue(request.operator(), "system")));
        }
        configFacade.upsertAdminValue(
                "notification.j4.rollback.requested",
                execId,
                "STRING",
                "content",
                "J4 rollback requires I3 correction/cancel follow-up");
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
            SopPlaybookRunRequest request,
            Map<String, Object> notificationDispatch,
            List<Map<String, Object>> domainActions) {
        List<Map<String, Object>> rows = new ArrayList<>(executionSeeds());
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
        rows.add(0, row);
        if (rows.size() > 50) {
            rows = new ArrayList<>(rows.subList(0, 50));
        }
        configFacade.upsertAdminValue(SOP_EXECUTIONS, toJson(rows), "JSON", GROUP_SOP, request.reason().trim());
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

    private void ensureSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        for (CountrySeed seed : countrySeeds()) {
            ensureConfig(countryConfigKey(seed.cc()), seed.status(), "STRING", GROUP_GEO_BLOCK, "J2 geo country status");
        }
        ensureConfig(GEO_CUSTOM_COUNTRIES, "", "STRING", GROUP_GEO_BLOCK, "J2 custom countries");
        ensureConfig(GEO_J4_BLOCK_REQUIRED, "false", "BOOLEAN", GROUP_GEO_BLOCK, "J4 J2 geo block escalation gate");
        ensureConfig(GEO_EDGE_SOURCE, "服务器边缘 IP 判定", "STRING", GROUP_GEO_BLOCK, "J2 edge IP judge source");
        ensureJsonConfig(GEO_EDGE_METRICS, defaultGeoEdgeMetrics(), GROUP_GEO_BLOCK, "J2 edge judge health metrics");
        ensureJsonConfig(GEO_HITS, defaultGeoHits(), GROUP_GEO_BLOCK, "J2 daily geo block hits");
        for (EndpointSeed seed : endpointSeeds()) {
            ensureConfig(endpointConfigKey(seed.key()), String.join(",", seed.countries()), "STRING", GROUP_GEO_BLOCK, "J2 endpoint geo countries");
            ensureConfig(endpointSourceConfigKey(seed.key()), seed.source(), "STRING", GROUP_GEO_BLOCK, "J2 endpoint geo source");
            ensureConfig(endpointSourceDescriptionConfigKey(seed.key()), seed.sourceDescription(), "STRING", GROUP_GEO_BLOCK, "J2 endpoint geo source description");
        }

        ensureConfig(TAMPER_THRESHOLD, "10", "NUMBER", GROUP_TAMPER, "J3 tamper account threshold");
        ensureConfig(TAMPER_FEED_K4, "true", "BOOLEAN", GROUP_TAMPER, "J3 tamper feed K4");
        ensureJsonConfig(TAMPER_TREND, defaultTamperTrend(), GROUP_TAMPER, "J3 tamper trend windows");
        ensureJsonConfig(TAMPER_PATHS, defaultTamperPaths(), GROUP_TAMPER, "J3 tamper attack paths");
        ensureJsonConfig(TAMPER_ACCOUNTS, defaultTamperAccounts(), GROUP_TAMPER, "J3 high frequency tamper accounts");

        ensureConfig(SOP_DRAFT_NAMES, "", "STRING", GROUP_SOP, "J4 SOP draft names");
        ensureJsonConfig(SOP_PLAYBOOKS, defaultPlaybookRows(), GROUP_SOP, "J4 emergency SOP playbooks");
        ensureJsonConfig(SOP_EXECUTIONS, defaultExecutions(), GROUP_SOP, "J4 emergency SOP execution history");
        ensureJsonConfig(SOP_ACTION_OPTIONS, defaultActionOptions(), GROUP_SOP, "J4 SOP selectable atomic actions");
        ensureJsonConfig(SOP_ROLLBACK_OPTIONS, defaultRollbackOptions(), GROUP_SOP, "J4 SOP selectable rollback templates");
    }

    private boolean readTimeBusinessSeedsDisabled() {
        return true;
    }

    private void ensureConfig(String key, String value, String valueType, String group, String remark) {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        if (activeValue(key).isEmpty()) {
            configFacade.upsertAdminValue(key, value, valueType, group, remark);
        }
    }

    private void ensureJsonConfig(String key, Object value, String group, String remark) {
        ensureConfig(key, toJson(value), "JSON", group, remark);
    }

    private List<Map<String, Object>> countryViews() {
        Set<String> countries = new LinkedHashSet<>();
        countries.addAll(readCsv(GEO_CUSTOM_COUNTRIES));
        return countries.stream()
                .map(this::countryView)
                .filter(row -> !"allowed".equals(row.get("status")))
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("status"))))
                .toList();
    }

    private Map<String, Object> countryView(String countryCode) {
        String cc = normalizeCountry(countryCode);
        Optional<CountrySeed> seed = countrySeed(cc);
        String status = activeValue(countryConfigKey(cc))
                .orElse("allowed");
        String name = seed.map(CountrySeed::name).orElse(cc);
        String reason = activeValue(countryConfigKey(cc) + ".reason")
                .orElse("");
        return map("cc", cc, "name", name, "status", status, "reason", reason);
    }

    private void writeCountryStatus(String countryCode, String status) {
        String cc = normalizeCountry(countryCode);
        configFacade.upsertAdminValue(countryConfigKey(cc), status, "STRING", GROUP_GEO_BLOCK, "J2 geo country status");
        Set<String> custom = new LinkedHashSet<>(readCsv(GEO_CUSTOM_COUNTRIES));
        if (countrySeed(cc).isEmpty() && !"allowed".equals(status)) {
            custom.add(cc);
        }
        if (countrySeed(cc).isEmpty() && "allowed".equals(status)) {
            custom.remove(cc);
        }
        configFacade.upsertAdminValue(GEO_CUSTOM_COUNTRIES, String.join(",", custom), "STRING", GROUP_GEO_BLOCK, "J2 custom countries");
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
        return endpointSeeds().stream()
                .filter(this::endpointConfigured)
                .map(this::endpointView)
                .toList();
    }

    private Map<String, Object> endpointView(EndpointSeed seed) {
        List<String> countries = activeValue(endpointConfigKey(seed.key()))
                .map(OpsEmergencyControlService::splitCsv)
                .orElseGet(List::of);
        String source = activeValue(endpointSourceConfigKey(seed.key()))
                .orElse("");
        return map(
                "key", seed.key(),
                "endpoint", seed.endpoint(),
                "label", seed.label(),
                "biz", seed.biz(),
                "domain", seed.domain(),
                "countries", countries,
                "source", source,
                "sourceLabel", switch (source) {
                    case "explicit" -> "单独设定";
                    case "derived" -> "继承全局";
                    default -> "待设置";
                },
                "sourceDescription", activeValue(endpointSourceDescriptionConfigKey(seed.key()))
                        .orElse(""),
                "hits", 0);
    }

    private boolean endpointConfigured(EndpointSeed seed) {
        return activeValue(endpointConfigKey(seed.key())).isPresent()
                || activeValue(endpointSourceConfigKey(seed.key())).isPresent()
                || activeValue(endpointSourceDescriptionConfigKey(seed.key())).isPresent();
    }

    private Map<String, Object> geoEdge() {
        return map(
                "source", activeValue(GEO_EDGE_SOURCE).orElse(""),
                "metrics", jsonList(GEO_EDGE_METRICS, List.of()));
    }

    private List<Map<String, Object>> geoHits() {
        return jsonList(GEO_HITS, List.of());
    }

    private Map<String, Object> tamperAlertConfig() {
        int threshold = activeValue(TAMPER_THRESHOLD)
                .map(Integer::parseInt)
                .orElse(0);
        boolean feedK4 = activeValue(TAMPER_FEED_K4)
                .map(Boolean::parseBoolean)
                .orElse(false);
        return map("threshold", threshold, "label", threshold + " 次 / 24h", "feedK4", feedK4);
    }

    private Map<String, Object> tamperTrend() {
        return jsonMap(TAMPER_TREND, Map.of());
    }

    private List<Map<String, Object>> tamperPaths() {
        return jsonList(TAMPER_PATHS, List.of());
    }

    private List<Map<String, Object>> tamperAccounts() {
        return jsonList(TAMPER_ACCOUNTS, List.of()).stream()
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

    private List<Map<String, Object>> defaultGeoEdgeMetrics() {
        return List.of(
                map("key", "判定延迟 P95", "value", "142 ms", "tone", "ok"),
                map("key", "判定健康度", "value", "99.8%", "tone", "ok"),
                map("key", "IP 解析失败率", "value", "0.04%", "tone", "ok"),
                map("key", "VPN / 代理处置", "value", "从严拦截", "tone", "warn"),
                map("key", "误判申诉", "value", "人工确认 · 7d SLA", "tone", ""),
                map("key", "最近源切换", "value", "21d 前 · ops@nexion", "tone", ""));
    }

    private List<Map<String, Object>> defaultGeoHits() {
        return List.of(
                map("cc", "KP", "name", "朝鲜", "count", 78),
                map("cc", "IR", "name", "伊朗", "count", 54),
                map("cc", "RU", "name", "俄罗斯", "count", 42),
                map("cc", "SY", "name", "叙利亚", "count", 21),
                map("cc", "CU", "name", "古巴", "count", 11),
                map("cc", "MM", "name", "缅甸", "count", 6));
    }

    private Map<String, Object> defaultTamperTrend() {
        return map(
                "24h", map("points", List.of(7, 5, 4, 4, 3, 2, 2, 1, 2, 3, 4, 6, 8, 9, 10, 11, 12, 12, 10, 9, 8, 7, 5, 3), "max", 15, "labels", List.of("0", "3", "6", "9", "12", "15", "18", "21")),
                "7d", map("points", List.of(156, 188, 174, 142, 195, 168, 161), "max", 240, "labels", List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")),
                "30d", map("points", List.of(142, 156, 138, 175, 168, 144, 152, 188, 195, 174, 162, 158, 172, 184, 169, 155, 148, 162, 178, 192, 184, 167, 159, 168, 175, 162, 158, 169, 184, 173), "max", 240, "labels", List.of("m30", "m25", "m20", "m15", "m10", "m5", "now")));
    }

    private List<Map<String, Object>> defaultTamperPaths() {
        return List.of(
                map("id", "local-balance", "name", "客户端余额改写", "description", "客户端篡改 balance", "count", 42, "accounts", 18, "color", "#FF6B35"),
                map("id", "price-override", "name", "价格越权", "description", "NEX/USDT 价格篡改", "count", 28, "accounts", 14, "color", "#FF8A5E"),
                map("id", "replay", "name", "请求重放", "description", "时间戳漂移 · 重复扣款", "count", 21, "accounts", 9, "color", "#FFA078"),
                map("id", "yield-fake", "name", "产出伪造", "description", "lvl 加速 / 设备产出篡改", "count", 18, "accounts", 11, "color", "#FFB390"),
                map("id", "ownership", "name", "权属冒用", "description", "claim node 非持有人", "count", 12, "accounts", 7, "color", "#FFBE3D"),
                map("id", "ab-group", "name", "A/B 分组", "description", "实验分组篡改污染口径", "count", 9, "accounts", 6, "color", "#FFC85F"),
                map("id", "client-version", "name", "版本回退", "description", "client 版本推进拦截", "count", 7, "accounts", 4, "color", "#FFD480"),
                map("id", "disclosure-ack", "name", "披露确认绕过", "description", "风险披露确认 server 重校验", "count", 5, "accounts", 3, "color", "#9B89E0"),
                map("id", "bills-push", "name", "Bills 推送", "description", "账本二次入账校验拒绝 client push", "count", 3, "accounts", 2, "color", "#8674D2"),
                map("id", "id-mint", "name", "ID-mint", "description", "client mint ID 撞键", "count", 2, "accounts", 1, "color", "#715FBE"));
    }

    private List<Map<String, Object>> defaultTamperAccounts() {
        return List.of(
                tamperAccount("U00083271", 24, "+42", "14:18:32", List.of("local-balance", "price-override", "replay"), "CL-318"),
                tamperAccount("U00077310", 18, "+36", "13:52:07", List.of("yield-fake", "ab-group"), "CL-318"),
                tamperAccount("U00066104", 14, "+28", "11:02:50", List.of("local-balance", "replay"), "CL-318"),
                tamperAccount("U00090233", 11, "+22", "11:58:33", List.of("ownership", "disclosure-ack"), ""),
                tamperAccount("U00051288", 9, "+18", "10:24:11", List.of("yield-fake", "client-version"), ""),
                tamperAccount("U00044821", 8, "+16", "09:47:28", List.of("replay", "bills-push"), ""),
                tamperAccount("U00029104", 6, "+12", "08:12:09", List.of("price-override"), "CL-292"),
                tamperAccount("U00019833", 5, "+10", "06:55:42", List.of("ab-group", "id-mint"), ""));
    }

    private List<Map<String, Object>> defaultExecutions() {
        return List.of(
                execution("2026-06-02 14:18", "SOP-06", "Genesis 价格异常", "genesis pump 0.50 vs server 0.0312 · 6 起篡改告警 / 90s", "emergency", List.of("done", "done", "done"), "risk@nexion", "super@nexion"),
                execution("2026-05-28 09:42", "SOP-03", "提现挤兑", "24h withdraw +480% · B1 yellowLine breach", "emergency", List.of("done", "done", "done", "skip"), "risk@nexion", "super@nexion"),
                execution("2026-05-22 11:08", "SOP-05", "篡改告警升级", "K1 簇 CL-318 · 12 账户 · 短持有 → 置换", "regular", List.of("done", "done", "done"), "risk@nexion", "risk-lead@nexion"),
                execution("2026-05-15 16:32", "SOP-07", "全站技术故障(演练)", "每月技术演练 · 维护窗口", "regular", List.of("done", "done", "done"), "tech-on-call", "super@nexion"),
                execution("2026-05-08 22:14", "SOP-02", "资金对账缺口", "D 域夜间对账 $3.2M 缺口告警", "emergency", List.of("done", "done", "done", "done"), "finance-lead", "super@nexion"),
                execution("2026-05-01 10:00", "SOP-01", "监管问询应答(演练)", "季度演练 · 模拟监管点名", "regular", List.of("done", "done", "done", "done"), "compliance", "super@nexion"));
    }

    private List<Map<String, Object>> draftPlaybooks(Set<String> existingCodes) {
        List<String> names = activeValue(SOP_DRAFT_NAMES).map(v -> split(v, "\\|")).orElse(List.of());
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String code = "SOP-DRAFT-" + (i + 1);
            if (existingCodes.contains(code)) {
                continue;
            }
            drafts.add(map(
                    "code", code,
                    "name", names.get(i),
                    "scene", "监管点名",
                    "emergency", false,
                    "sla", "未设置",
                    "state", "todo",
                    "owner", "未分配",
                    "lastDrill", "未演练",
                    "sequence", List.of(),
                    "draft", true));
        }
        return drafts;
    }

    private Map<String, Object> playbookView(PlaybookSeed seed) {
        Optional<String> drillAt = activeValue(playbookConfigKey(seed.code(), "drillAt"));
        return map(
                "code", seed.code(),
                "name", seed.name(),
                "scene", seed.scene(),
                "emergency", seed.emergency(),
                "sla", seed.sla(),
                "state", drillAt.isPresent() ? "active" : seed.state(),
                "owner", seed.owner(),
                "lastDrill", drillAt.map(value -> "刚刚 · 沙箱").orElse(seed.lastDrill()),
                "sequence", seed.seq(),
                "notifyCampaignNo", seed.notifyCampaignNo(),
                "notifyTemplate", seed.notifyTemplate(),
                "rollback", seed.rollback(),
                "drillRequired", seed.drillRequired(),
                "draft", seed.draft(),
                "customSummary", activeValue(playbookConfigKey(seed.code(), "summary")).orElse(null),
                "lastExecution", activeValue(playbookConfigKey(seed.code(), "lastExecution")).orElse(null));
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
        List<Map<String, Object>> rows = new ArrayList<>(jsonList(SOP_PLAYBOOKS, List.of()));
        for (Map<String, Object> row : rows) {
            String rowCode = stringValue(row.get("code"), "").toUpperCase(Locale.ROOT);
            if (!code.equals(rowCode)) {
                continue;
            }
            if (StringUtils.hasText(request.name())) row.put("name", request.name().trim());
            if (StringUtils.hasText(request.scene())) row.put("scene", request.scene().trim());
            if (StringUtils.hasText(request.owner())) row.put("owner", request.owner().trim());
            if (StringUtils.hasText(request.sla())) row.put("sla", request.sla().trim());
            if (request.emergencyTrack() != null) row.put("emergency", request.emergencyTrack());
            if (StringUtils.hasText(request.actionSeq())) row.put("sequence", parseActionSequence(request.actionSeq()));
            if (StringUtils.hasText(request.notifyCampaignNo())) row.put("notifyCampaignNo", request.notifyCampaignNo().trim());
            if (StringUtils.hasText(request.notifyTemplate())) row.put("notifyTemplate", request.notifyTemplate().trim());
            if (StringUtils.hasText(request.rollback())) row.put("rollback", request.rollback().trim());
            if (request.drillRequired() != null) row.put("drillRequired", request.drillRequired());
            configFacade.upsertAdminValue(SOP_PLAYBOOKS, toJson(rows), "JSON", GROUP_SOP, request.reason().trim());
            return row;
        }
        return playbookRow(playbookSeed(code));
    }

    private List<Map<String, Object>> executionSeeds() {
        return jsonList(SOP_EXECUTIONS, List.of());
    }

    private Map<String, Object> execution(String ts, String code, String name, String trigger, String mode, List<String> steps, String operator, String roleGate) {
        return map("timestamp", ts, "code", code, "name", name, "trigger", trigger, "mode", mode, "steps", steps, "operator", operator, "roleGate", roleGate);
    }

    private List<CountrySeed> countrySeeds() {
        return List.of(
                new CountrySeed("KP", "朝鲜", "blocked", "OFAC 制裁名单"),
                new CountrySeed("IR", "伊朗", "blocked", "OFAC + FATF 制裁名单"),
                new CountrySeed("SY", "叙利亚", "blocked", "OFAC 制裁名单"),
                new CountrySeed("CN", "中国大陆", "limited", "金融监管 · 证券类风险"),
                new CountrySeed("US", "美国", "limited", "SEC 合规未取得"),
                new CountrySeed("RU", "俄罗斯", "limited", "OFAC 金融制裁"),
                new CountrySeed("CU", "古巴", "limited", "OFAC 制裁名单"),
                new CountrySeed("MM", "缅甸", "limited", "FATF 高风险地区"));
    }

    private Optional<CountrySeed> countrySeed(String countryCode) {
        return countrySeeds().stream().filter(seed -> seed.cc().equals(countryCode)).findFirst();
    }

    private List<EndpointSeed> endpointSeeds() {
        return List.of(
                new EndpointSeed("genesis", "/genesis/*", "创世节点购买", "Genesis 经济", "G4", List.of("KP", "IR", "SY", "CN", "US"), "explicit", "前端显式声明屏蔽国家", 104),
                new EndpointSeed("exchange-swap", "/exchange/swap", "NEX 兑换", "NEX 兑换", "G2", List.of("KP", "IR", "SY", "RU"), "derived", "OFAC/FATF 链路派生", 62),
                new EndpointSeed("tradein", "/tradein/*", "以旧换新", "设备生命周期", "E3", List.of(), "pending", "V4 待补收口", 0),
                new EndpointSeed("withdraw", "/withdraw/*", "提现", "资金提现", "D2", List.of("KP", "IR", "SY"), "derived", "承袭全局黑名单", 24),
                new EndpointSeed("staking", "/staking/pool/*", "质押池", "Staking 锁仓", "G1", List.of("KP", "IR", "SY"), "derived", "承袭全局黑名单", 11),
                new EndpointSeed("register", "/auth/register", "注册", "账户与登录", "C1", List.of("KP", "IR", "SY"), "derived", "承袭全局黑名单", 8),
                new EndpointSeed("wallet-exchange", "/me/wallet/exchange", "钱包内兑换", "NEX 兑换", "G2", List.of("KP", "IR", "SY", "RU"), "derived", "承袭 + OFAC/FATF", 3));
    }

    private EndpointSeed endpointSeed(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return endpointSeeds().stream()
                .filter(seed -> seed.key().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("GEO_ENDPOINT_NOT_FOUND"));
    }

    private List<PlaybookSeed> playbookSeeds() {
        return jsonList(SOP_PLAYBOOKS, List.of()).stream()
                .map(this::playbookFromRow)
                .toList();
    }

    private List<Map<String, Object>> defaultPlaybookRows() {
        return defaultPlaybookSeeds().stream().map(this::playbookRow).toList();
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
                boolValue(row.get("draft")));
    }

    private List<PlaybookSeed> defaultPlaybookSeeds() {
        return List.of(
                playbook("SOP-01", "监管问询应答", "监管点名", true, "≤ 2h", "active", "合规主管", "12d",
                        List.of(step("J1", "熔断 **exchange + genesis**", true, null), step("I5", "切换 **风险披露** v2.4 → v2.5", true, "jurisdiction=any"), step("C2", "**冻结取证** 涉事账户", true, null), step("I3", "通知 **法务对接** 模板", false, null))),
                playbook("SOP-02", "资金对账缺口", "资金异常", true, "≤ 1h", "active", "财务主管", "8d",
                        List.of(step("J1", "熔断 **withdraw** 提现", true, "D2"), step("B1", "核对 **双账本** coverageRatio", false, null), step("D2", "定位敞口 → **分批放行**", true, null), step("I3", "通知 **财务上报**", false, null))),
                playbook("SOP-03", "提现挤兑", "舆情挤兑", true, "≤ 30m", "active", "风控主管", "5d",
                        List.of(step("D2", "**限流** 提现 50% 降速", true, null), step("I3", "**公告披露** critical 通知", false, null), step("D2", "按 B1 容量 **分批放行**", true, null), step("I5", "风险披露 v2.6 **临时附录**", true, null))),
                playbook("SOP-04", "数据泄露响应", "数据泄露", false, "≤ 1h", "todo", "安全主管", "92d",
                        List.of(step("J1", "**隔离** 受影响子系统", true, null), step("I3", "**影响评估** 通知模板", false, null), step("I3", "**用户通知** critical", false, null), step("J1", "加固后 **恢复**", true, null))),
                playbook("SOP-05", "篡改告警升级", "资金异常", true, "≤ 45m", "active", "风控主管", "14d",
                        List.of(step("C2", "**定向冻结** 高频篡改账户", true, null), step("K1", "批量簇 **建档调查**", true, null), step("I3", "通知 **风控上报**", false, null))),
                playbook("SOP-06", "Genesis 价格异常", "资金异常", true, "≤ 30m", "active", "风控主管", "18d",
                        List.of(step("J1", "熔断 **genesis** 二级市场", true, null), step("I3", "**快照定格** + 公告披露", false, null), step("J1", "核因后 **恢复**(前置 B1)", true, "B1.coverage"))),
                playbook("SOP-07", "全站技术故障", "技术故障", false, "≤ 20m", "active", "技术值班", "3d",
                        List.of(step("J1", "进入 **维护模式**", true, null), step("I3", "**根因定位** + 通告", false, null), step("J1", "**灰度恢复**", true, null))),
                playbook("SOP-08", "地域合规收紧", "监管点名", false, "≤ 4h", "todo", "合规主管", "127d",
                        List.of(step("J2", "**升级封禁** 国家黑名单", true, null), step("C2", "存量账户转 **只读**", true, null), step("I3", "**退出引导** 通知", false, null), step("I5", "**留痕** jurisdiction 版本", false, null))));
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

    private PlaybookSeed playbook(String code, String name, String scene, boolean emergency, String sla, String state, String owner, String lastDrill, List<Map<String, Object>> seq) {
        String notify = seq.stream()
                .filter(row -> "I3".equals(String.valueOf(row.get("domain"))))
                .map(row -> String.valueOf(row.get("action")).replace("**", ""))
                .findFirst()
                .orElse("");
        return new PlaybookSeed(code, name, scene, emergency, sla, state, owner, lastDrill, seq, "", notify, "", true, false);
    }

    private Map<String, Object> step(String domain, String action, boolean approve, String ref) {
        return map("domain", domain, "action", action, "approve", approve, "ref", ref);
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
        int max = readCsv(SOP_DRAFT_NAMES).size();
        for (Map<String, Object> row : rows) {
            String code = stringValue(row.get("code"), "");
            if (!code.startsWith("SOP-DRAFT-")) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(code.substring("SOP-DRAFT-".length())));
            } catch (NumberFormatException ignored) {
                max = Math.max(max, 0);
            }
        }
        return "SOP-DRAFT-" + (max + 1);
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
                    return step(domain, action, !"I3".equals(domain), null);
                })
                .toList();
    }

    private Optional<String> activeValue(String configKey) {
        return configFacade.activeValue(configKey);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("EMERGENCY_CONFIG_JSON_WRITE_FAILED", e);
        }
    }

    private List<Map<String, Object>> jsonList(String configKey, List<Map<String, Object>> fallback) {
        return activeValue(configKey)
                .map(value -> {
                    try {
                        return objectMapper.readValue(value, LIST_MAP_TYPE);
                    } catch (Exception e) {
                        return List.<Map<String, Object>>of();
                    }
                })
                .orElseGet(List::of);
    }

    private List<Map<String, Object>> enumJsonList(String configKey, List<Map<String, Object>> fallback) {
        return activeValue(configKey)
                .map(value -> {
                    try {
                        return objectMapper.readValue(value, LIST_MAP_TYPE);
                    } catch (Exception e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private Map<String, Object> jsonMap(String configKey, Map<String, Object> fallback) {
        return activeValue(configKey)
                .map(value -> {
                    try {
                        return objectMapper.readValue(value, MAP_TYPE);
                    } catch (Exception e) {
                        return Map.<String, Object>of();
                    }
                })
                .orElseGet(Map::of);
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

    private String sanitizeConfigPart(String value) {
        String normalized = stringValue(value, "missing").replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
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

    private List<String> readCsv(String configKey) {
        return activeValue(configKey).map(OpsEmergencyControlService::splitCsv).orElse(List.of());
    }

    private static List<String> splitCsv(String raw) {
        return split(raw, ",");
    }

    private static List<String> split(String raw, String delimiterRegex) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(delimiterRegex))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String countryConfigKey(String countryCode) {
        return "emergency.geo.country." + normalizeCountry(countryCode);
    }

    private String endpointConfigKey(String endpointKey) {
        return "emergency.geo.endpoint." + endpointKey + ".countries";
    }

    private String endpointSourceConfigKey(String endpointKey) {
        return "emergency.geo.endpoint." + endpointKey + ".source";
    }

    private String endpointSourceDescriptionConfigKey(String endpointKey) {
        return "emergency.geo.endpoint." + endpointKey + ".sourceDescription";
    }

    private String playbookConfigKey(String code, String field) {
        return "emergency.sop.playbook." + code + "." + field;
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

    private record CountrySeed(String cc, String name, String status, String reason) {
    }

    private record EndpointSeed(String key, String endpoint, String label, String biz, String domain, List<String> countries,
                                String source, String sourceDescription, int hits) {
    }

    private record PlaybookSeed(String code, String name, String scene, boolean emergency, String sla, String state,
                                String owner, String lastDrill, List<Map<String, Object>> seq,
                                String notifyCampaignNo, String notifyTemplate, String rollback,
                                boolean drillRequired, boolean draft) {
    }
}
