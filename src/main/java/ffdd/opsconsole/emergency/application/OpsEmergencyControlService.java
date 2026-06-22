package ffdd.opsconsole.emergency.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.GeoEdgeJudgeRequest;
import ffdd.opsconsole.emergency.dto.GeoEmergencyBlockRequest;
import ffdd.opsconsole.emergency.dto.GeoEndpointCountriesRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookCreateRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookUpdateRequest;
import ffdd.opsconsole.emergency.dto.TamperAlertConfigRequest;
import ffdd.opsconsole.emergency.dto.TamperReportRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
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
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsEmergencyControlService {
    private static final Pattern ISO_COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String GEO_CUSTOM_COUNTRIES = "emergency.geo.customCountries";
    private static final String GEO_EDGE_SOURCE = "emergency.geo.edgeJudgeSource";
    private static final String TAMPER_THRESHOLD = "emergency.tamper.alert.threshold";
    private static final String TAMPER_FEED_K4 = "emergency.tamper.alert.feedK4";
    private static final String SOP_DRAFT_NAMES = "emergency.sop.draftNames";

    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> geoBlockOverview() {
        List<Map<String, Object>> countries = countryViews();
        List<Map<String, Object>> blocked = countries.stream()
                .filter(row -> "blocked".equals(row.get("status")))
                .toList();
        List<Map<String, Object>> limited = countries.stream()
                .filter(row -> "limited".equals(row.get("status")))
                .toList();
        List<Map<String, Object>> hits = geoHits();
        int totalHits = hits.stream().mapToInt(row -> (Integer) row.get("count")).sum();
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
                        "health", "99.8%",
                        "confirmSlaMins", activeValue("ops.J.emergency.confirmSlaMins").orElse("15")),
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
        configFacade.upsertAdminValue(endpointConfigKey(endpoint.key()), String.join(",", countries), "STRING", "admin_geo_block", request.reason().trim());
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
        configFacade.upsertAdminValue(GEO_EDGE_SOURCE, source, "STRING", "admin_geo_block", request.reason().trim());
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
        int today = tamperPaths().stream().mapToInt(row -> (Integer) row.get("count")).sum();
        int sevenDay = ((List<Integer>) ((Map<String, Object>) tamperTrend().get("7d")).get("points"))
                .stream()
                .mapToInt(Integer::intValue)
                .sum();
        Map<String, Object> response = map(
                "domain", "J3",
                "stats", map(
                        "todayBlocked", today,
                        "highFrequencyAccounts", tamperAccounts().size(),
                        "sevenDayBlocked", sevenDay,
                        "lossUsd", 0),
                "trend", tamperTrend(),
                "paths", tamperPaths(),
                "accounts", tamperAccounts(),
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
        configFacade.upsertAdminValue(TAMPER_THRESHOLD, String.valueOf(threshold), "NUMBER", "admin_tamper", request.reason().trim());
        configFacade.upsertAdminValue(TAMPER_FEED_K4, String.valueOf(feedK4), "BOOLEAN", "admin_tamper", request.reason().trim());
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
        List<Map<String, Object>> playbooks = new ArrayList<>(playbookSeeds().stream().map(this::playbookView).toList());
        playbooks.addAll(draftPlaybooks());
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
                        "drill90d", executions.stream().filter(row -> String.valueOf(row.get("name")).contains("(演练)")).count() + 12),
                "sla", map(
                        "confirmSlaMins", activeValue("ops.J.emergency.confirmSlaMins").orElse("15"),
                        "escalateMaxMins", activeValue("ops.J.emergency.escalateMaxMins").orElse("60"),
                        "escalateMaxRounds", activeValue("ops.J.emergency.escalateMaxRounds").orElse("4")),
                "scenes", List.of("全部", "监管点名", "资金异常", "数据泄露", "舆情挤兑", "技术故障"),
                "playbooks", playbooks,
                "executions", executions,
                "sources", List.of("nx_config_item:emergency.sop.*", "A2 emergency playbook audit"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createPlaybook(String idempotencyKey, SopPlaybookCreateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(request.name())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PLAYBOOK_NAME_REQUIRED");
        }
        Set<String> names = new LinkedHashSet<>(readCsv(SOP_DRAFT_NAMES));
        names.add(request.name().trim());
        configFacade.upsertAdminValue(SOP_DRAFT_NAMES, String.join("|", names), "STRING", "admin_sop", request.reason().trim());
        audit("J4_SOP_PLAYBOOK_CREATED", "SOP_PLAYBOOK", request.name().trim(), request.operator(), "HIGH", map(
                "name", request.name().trim(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return sopOverview();
    }

    public ApiResult<Map<String, Object>> updatePlaybook(String code, String idempotencyKey, SopPlaybookUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        PlaybookSeed seed = playbookSeed(code);
        String summary = StringUtils.hasText(request.summary()) ? request.summary().trim() : seed.seq().size() + " steps";
        configFacade.upsertAdminValue(playbookConfigKey(seed.code(), "summary"), summary, "STRING", "admin_sop", request.reason().trim());
        audit("J4_SOP_PLAYBOOK_EDITED", "SOP_PLAYBOOK", seed.code(), request.operator(), "HIGH", map(
                "code", seed.code(),
                "summary", summary,
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
        configFacade.upsertAdminValue(playbookConfigKey(seed.code(), "drillAt"), now, "STRING", "admin_sop", request.reason().trim());
        audit("J4_SOP_PLAYBOOK_DRILL_STARTED", "SOP_PLAYBOOK", seed.code(), request.operator(), "MEDIUM", map(
                "code", seed.code(),
                "sandbox", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return sopOverview();
    }

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
        String execId = seed.code() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        configFacade.upsertAdminValue(playbookConfigKey(seed.code(), "lastExecution"), execId, "STRING", "admin_sop", request.reason().trim());
        audit("J4_SOP_PLAYBOOK_EXECUTED", "SOP_PLAYBOOK_EXECUTION", execId, request.operator(), emergency ? "CRITICAL" : "HIGH", map(
                "code", seed.code(),
                "emergency", emergency,
                "steps", seed.seq(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = sopOverview().getData();
        response.put("updated", map("executionId", execId, "code", seed.code(), "emergency", emergency));
        return ApiResult.ok(response);
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
        Set<String> countries = new LinkedHashSet<>();
        countrySeeds().forEach(seed -> countries.add(seed.cc()));
        countries.addAll(readCsv(GEO_CUSTOM_COUNTRIES));
        return countries.stream()
                .map(this::countryView)
                .filter(row -> !"allowed".equals(row.get("status")) || countrySeed((String) row.get("cc")).isPresent())
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("status"))))
                .toList();
    }

    private Map<String, Object> countryView(String countryCode) {
        String cc = normalizeCountry(countryCode);
        Optional<CountrySeed> seed = countrySeed(cc);
        String status = activeValue(countryConfigKey(cc)).orElse(seed.map(CountrySeed::status).orElse("allowed"));
        String name = seed.map(CountrySeed::name).orElse(cc);
        String reason = seed.map(CountrySeed::reason).orElse("运营加入 · A2 留痕");
        return map("cc", cc, "name", name, "status", status, "reason", reason);
    }

    private void writeCountryStatus(String countryCode, String status) {
        String cc = normalizeCountry(countryCode);
        configFacade.upsertAdminValue(countryConfigKey(cc), status, "STRING", "admin_geo_block", "J2 geo country status");
        Set<String> custom = new LinkedHashSet<>(readCsv(GEO_CUSTOM_COUNTRIES));
        if (countrySeed(cc).isEmpty() && !"allowed".equals(status)) {
            custom.add(cc);
        }
        if (countrySeed(cc).isEmpty() && "allowed".equals(status)) {
            custom.remove(cc);
        }
        configFacade.upsertAdminValue(GEO_CUSTOM_COUNTRIES, String.join(",", custom), "STRING", "admin_geo_block", "J2 custom countries");
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
        return endpointSeeds().stream().map(this::endpointView).toList();
    }

    private Map<String, Object> endpointView(EndpointSeed seed) {
        List<String> countries = activeValue(endpointConfigKey(seed.key()))
                .map(OpsEmergencyControlService::splitCsv)
                .orElse(seed.countries());
        return map(
                "key", seed.key(),
                "endpoint", seed.endpoint(),
                "label", seed.label(),
                "biz", seed.biz(),
                "domain", seed.domain(),
                "countries", countries,
                "source", activeValue(endpointConfigKey(seed.key())).isPresent() ? "explicit" : seed.source(),
                "sourceLabel", switch (activeValue(endpointConfigKey(seed.key())).isPresent() ? "explicit" : seed.source()) {
                    case "explicit" -> "单独设定";
                    case "derived" -> "继承全局";
                    default -> "待设置";
                },
                "sourceDescription", seed.sourceDescription(),
                "hits", seed.hits());
    }

    private Map<String, Object> geoEdge() {
        return map(
                "source", activeValue(GEO_EDGE_SOURCE).orElse("服务器边缘 IP 判定"),
                "metrics", List.of(
                        map("key", "判定延迟 P95", "value", "142 ms", "tone", "ok"),
                        map("key", "判定健康度", "value", "99.8%", "tone", "ok"),
                        map("key", "IP 解析失败率", "value", "0.04%", "tone", "ok"),
                        map("key", "VPN / 代理处置", "value", "从严拦截", "tone", "warn"),
                        map("key", "误判申诉", "value", "人工确认 · 7d SLA", "tone", ""),
                        map("key", "最近源切换", "value", "21d 前 · ops@nexion", "tone", "")));
    }

    private List<Map<String, Object>> geoHits() {
        return List.of(
                map("cc", "KP", "name", "朝鲜", "count", 78),
                map("cc", "IR", "name", "伊朗", "count", 54),
                map("cc", "RU", "name", "俄罗斯", "count", 42),
                map("cc", "SY", "name", "叙利亚", "count", 21),
                map("cc", "CU", "name", "古巴", "count", 11),
                map("cc", "MM", "name", "缅甸", "count", 6));
    }

    private Map<String, Object> tamperAlertConfig() {
        int threshold = activeValue(TAMPER_THRESHOLD).map(Integer::parseInt).orElse(10);
        boolean feedK4 = activeValue(TAMPER_FEED_K4).map(Boolean::parseBoolean).orElse(true);
        return map("threshold", threshold, "label", threshold + " 次 / 24h", "feedK4", feedK4);
    }

    private Map<String, Object> tamperTrend() {
        return map(
                "24h", map("points", List.of(7, 5, 4, 4, 3, 2, 2, 1, 2, 3, 4, 6, 8, 9, 10, 11, 12, 12, 10, 9, 8, 7, 5, 3), "max", 15, "labels", List.of("0", "3", "6", "9", "12", "15", "18", "21")),
                "7d", map("points", List.of(156, 188, 174, 142, 195, 168, 161), "max", 240, "labels", List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")),
                "30d", map("points", List.of(142, 156, 138, 175, 168, 144, 152, 188, 195, 174, 162, 158, 172, 184, 169, 155, 148, 162, 178, 192, 184, 167, 159, 168, 175, 162, 158, 169, 184, 173), "max", 240, "labels", List.of("m30", "m25", "m20", "m15", "m10", "m5", "now")));
    }

    private List<Map<String, Object>> tamperPaths() {
        return List.of(
                map("id", "local-balance", "name", "客户端余额改写", "description", "客户端篡改 balance", "count", 42, "accounts", 18, "color", "#FF6B35"),
                map("id", "price-override", "name", "价格越权", "description", "NEX/USDT 价格篡改", "count", 28, "accounts", 14, "color", "#FF8A5E"),
                map("id", "replay", "name", "请求重放", "description", "时间戳漂移 · 重复扣款", "count", 21, "accounts", 9, "color", "#FFA078"),
                map("id", "yield-fake", "name", "产出伪造", "description", "lvl 加速 / 设备产出篡改", "count", 18, "accounts", 11, "color", "#FFB390"),
                map("id", "ownership", "name", "权属冒用", "description", "claim node 非持有人", "count", 12, "accounts", 7, "color", "#FFBE3D"),
                map("id", "ab-group", "name", "A/B 分组", "description", "实验分组篡改污染口径", "count", 9, "accounts", 6, "color", "#FFC85F"),
                map("id", "client-version", "name", "版本回退", "description", "client 版本推进拦截", "count", 7, "accounts", 4, "color", "#FFD480"),
                map("id", "disclosure-ack", "name", "I5 ack 绕过", "description", "风险披露 ack server 重校验", "count", 5, "accounts", 3, "color", "#9B89E0"),
                map("id", "bills-push", "name", "Bills 推送", "description", "账本二次入账校验拒绝 client push", "count", 3, "accounts", 2, "color", "#8674D2"),
                map("id", "id-mint", "name", "ID-mint", "description", "client mint ID 撞键", "count", 2, "accounts", 1, "color", "#715FBE"));
    }

    private List<Map<String, Object>> tamperAccounts() {
        return List.of(
                tamperAccount("u-83271", 24, "+42", "14:18:32", List.of("local-balance", "price-override", "replay"), "CL-318"),
                tamperAccount("u-77310", 18, "+36", "13:52:07", List.of("yield-fake", "ab-group"), "CL-318"),
                tamperAccount("u-66104", 14, "+28", "11:02:50", List.of("local-balance", "replay"), "CL-318"),
                tamperAccount("u-90233", 11, "+22", "11:58:33", List.of("ownership", "disclosure-ack"), ""),
                tamperAccount("u-51288", 9, "+18", "10:24:11", List.of("yield-fake", "client-version"), ""),
                tamperAccount("u-44821", 8, "+16", "09:47:28", List.of("replay", "bills-push"), ""),
                tamperAccount("u-29104", 6, "+12", "08:12:09", List.of("price-override"), "CL-292"),
                tamperAccount("u-19833", 5, "+10", "06:55:42", List.of("ab-group", "id-mint"), ""));
    }

    private Map<String, Object> tamperAccount(String uid, int count, String k4, String last, List<String> paths, String cluster) {
        return map("uid", uid, "count", count, "k4", k4, "last", last, "paths", paths, "cluster", cluster);
    }

    private List<Map<String, Object>> draftPlaybooks() {
        List<String> names = activeValue(SOP_DRAFT_NAMES).map(v -> split(v, "\\|")).orElse(List.of());
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String code = "SOP-DRAFT-" + (i + 1);
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
                "customSummary", activeValue(playbookConfigKey(seed.code(), "summary")).orElse(null),
                "lastExecution", activeValue(playbookConfigKey(seed.code(), "lastExecution")).orElse(null));
    }

    private List<Map<String, Object>> executionSeeds() {
        return List.of(
                execution("2026-06-02 14:18", "SOP-06", "Genesis 价格异常", "genesis pump 0.50 vs server 0.0312 · 6 起篡改告警 / 90s", "emergency", List.of("done", "done", "done"), "risk@nexion", "super@nexion"),
                execution("2026-05-28 09:42", "SOP-03", "提现挤兑", "24h withdraw +480% · B1 yellowLine breach", "emergency", List.of("done", "done", "done", "skip"), "risk@nexion", "super@nexion"),
                execution("2026-05-22 11:08", "SOP-05", "篡改告警升级", "K1 簇 CL-318 · 12 账户 · 短持有 → 置换", "regular", List.of("done", "done", "done"), "risk@nexion", "risk-lead@nexion"),
                execution("2026-05-15 16:32", "SOP-07", "全站技术故障(演练)", "每月技术演练 · 维护窗口", "regular", List.of("done", "done", "done"), "tech-on-call", "super@nexion"),
                execution("2026-05-08 22:14", "SOP-02", "资金对账缺口", "D 域夜间对账 $3.2M 缺口告警", "emergency", List.of("done", "done", "done", "done"), "finance-lead", "super@nexion"),
                execution("2026-05-01 10:00", "SOP-01", "监管问询应答(演练)", "季度演练 · 模拟监管点名", "regular", List.of("done", "done", "done", "done"), "compliance", "super@nexion"));
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
        return playbookSeeds().stream()
                .filter(seed -> seed.code().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SOP_PLAYBOOK_NOT_FOUND"));
    }

    private PlaybookSeed playbook(String code, String name, String scene, boolean emergency, String sla, String state, String owner, String lastDrill, List<Map<String, Object>> seq) {
        return new PlaybookSeed(code, name, scene, emergency, sla, state, owner, lastDrill, seq);
    }

    private Map<String, Object> step(String domain, String action, boolean approve, String ref) {
        return map("domain", domain, "action", action, "approve", approve, "ref", ref);
    }

    private Optional<String> activeValue(String configKey) {
        return configFacade.activeValue(configKey);
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
                                String owner, String lastDrill, List<Map<String, Object>> seq) {
    }
}
