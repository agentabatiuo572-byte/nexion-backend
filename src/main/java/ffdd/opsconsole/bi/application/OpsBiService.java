package ffdd.opsconsole.bi.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.domain.BiReportDownloadFile;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.BiDashboardValueRequest;
import ffdd.opsconsole.bi.dto.BiRegulatoryTemplateRequest;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportCreateRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryFinanceAnalyticsFacade;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsBiService implements AuditReplayable {
    private static final Set<String> ACTIONS = Set.of("GENERATE", "RERUN", "APPROVE", "DOWNLOAD");
    private static final Set<String> AGGREGATE_EXPORT_TYPES = Set.of(
            "KPI_SERIES", "FUNNEL_COHORT", "FINANCE_AGG", "OPERATIONS_AGG");
    private static final Set<String> DOWNLOADABLE_REPORT_TYPES = Set.of(
            "KPI_SERIES", "FUNNEL_COHORT", "FINANCE_AGG", "OPERATIONS_AGG", "NETWORK_TREE", "KYC_REGULATORY", "REGULATORY");
    private static final int NETWORK_TREE_ROW_CAP = 100_000;

    private final BiReportRepository reportRepository;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final TreasuryLedgerRepository ledgerRepository;
    private final TreasuryFinanceAnalyticsFacade financeAnalyticsFacade;
    private final AuditLogService auditLogService;
    private final AdminPermissionCache permissionCache;
    private final AuditObjectLockMapper lockMapper;
    private final AdminIdempotencyService idempotencyService;

    public ApiResult<Map<String, Object>> overview() {
        Long adminId = parseAdminIdFromContext();
        Set<String> permissions = adminId == null ? Set.of() : permissionCache.getPermissionCodes(adminId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "L");
        response.put("currentPhase", currentPhase());
        response.put("phases", phases());
        if (permissions.contains("bi_l1_read")) response.put("l1", reportRepository.dashboard("L1"));
        if (permissions.contains("bi_l2_read")) response.put("l2", reportRepository.dashboard("L2"));
        if (permissions.contains("bi_l3_read")) response.put("l3", reportRepository.dashboard("L3"));
        if (permissions.contains("bi_l4_read")) response.put("l4", reportRepository.dashboard("L4"));
        if (permissions.contains("bi_l5_read")) {
            response.putAll(reportRepository.overview());
            response.put("maskingRules", maskingRules());
            response.put("securityParams", List.of(
                    "sensitive exports require confirm-with-reason and A2 audit",
                    "download token TTL is 24 hours",
                    "decrypted PII export is blocked in active admin API"));
            response.put("l5", exportOverview().getData());
        }
        if (permissions.contains("bi_l6_read")) response.put("l6", behaviorHeatmapOverview("7d").getData());
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> kpiOverview() {
        return ApiResult.ok(reportRepository.dashboard("L1"));
    }

    public ApiResult<Map<String, Object>> kpiOverview(
            String window, String cohort, String phase, String locale, String ref) {
        return ApiResult.ok(reportRepository.kpiDashboard(window, cohort, phase, locale, ref));
    }

    public ApiResult<Map<String, Object>> kpiDrilldown(
            int kpiId, String window, String cohort, String phase, String locale, String ref) {
        if (kpiId < 1 || kpiId > 8) {
            return ApiResult.fail(400, "L1_KPI_ID_INVALID");
        }
        return ApiResult.ok(reportRepository.kpiDrilldown(kpiId, window, cohort, phase, locale, ref));
    }

    public ApiResult<Map<String, Object>> kpiTrend(
            int kpiId, String window, String cohort, String phase, String locale, String ref) {
        if (kpiId < 1 || kpiId > 8) {
            return ApiResult.fail(400, "L1_KPI_ID_INVALID");
        }
        return ApiResult.ok(reportRepository.kpiTrend(kpiId, window, cohort, phase, locale, ref));
    }

    public ApiResult<Map<String, Object>> funnelOverview() {
        return ApiResult.ok(reportRepository.dashboard("L2"));
    }

    public ApiResult<Map<String, Object>> funnelDrilldown(
            String stage, String cohort, String phase, String locale, String ref) {
        Map<String, Object> dashboard = reportRepository.dashboard("L2");
        Map<String, Object> response = l2ReadResponse(dashboard, stage, cohort, phase, locale, ref);
        response.put("funnel", dashboard.getOrDefault("funnel", List.of()));
        response.put("funnelExt", dashboard.getOrDefault("funnelExt", List.of()));
        response.put("trialSteps", dashboard.getOrDefault("trialSteps", List.of()));
        response.put("stageEvents", dashboard.getOrDefault("stageEvents", List.of()));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> retentionCohortMatrix(String cohortRange, String window) {
        Map<String, Object> dashboard = reportRepository.dashboard("L2");
        Map<String, Object> response = l2ReadResponse(dashboard, null, cohortRange, null, null, null);
        response.put("window", trimOrDefault(window, "Day1,Day7,Day30"));
        response.put("cohorts", dashboard.getOrDefault("cohorts", List.of()));
        response.put("retentionEvent", "app.dau");
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> retentionCurve(String cohort) {
        Map<String, Object> dashboard = reportRepository.dashboard("L2");
        Map<String, Object> response = l2ReadResponse(dashboard, null, cohort, null, null, null);
        Map<String, Object> curves = mutableObjectMap(dashboard.get("curves"));
        response.put("curve", StringUtils.hasText(cohort) ? curves.getOrDefault(cohort.trim(), List.of()) : curves);
        response.put("retentionEvent", "app.dau");
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> funnelCross(String dim1, String dim2, String metric) {
        Map<String, Object> dashboard = reportRepository.dashboard("L2");
        Map<String, Object> response = l2ReadResponse(dashboard, null, null, dim1, dim2, null);
        response.put("metric", trimOrDefault(metric, "cvr"));
        response.put("crossAnalysis", dashboard.getOrDefault("crossAnalysis", Map.of()));
        return ApiResult.ok(response);
    }

    private Map<String, Object> l2ReadResponse(
            Map<String, Object> dashboard,
            String stage,
            String cohort,
            String phase,
            String locale,
            String ref) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("module", "L2");
        response.put("available", dashboard.containsKey("funnel"));
        response.put("filters", linked(
                "stage", trimOrDefault(stage, ""),
                "cohort", trimOrDefault(cohort, ""),
                "phase", trimOrDefault(phase, ""),
                "locale", trimOrDefault(locale, ""),
                "ref", trimOrDefault(ref, "")));
        response.put("quality", dashboard.getOrDefault("quality", Map.of(
                "sameUserJoin", false,
                "incompleteRatesAreNull", true)));
        response.put("sources", dashboard.getOrDefault("sources", List.of()));
        return response;
    }

    public ApiResult<Map<String, Object>> financeOverview() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.dashboard("L3"));
        response.put("ledgerLive", ledgerLiveSummary());
        Map<String, Object> financeLive = financeAnalyticsFacade.currentFinanceSnapshot();
        if (financeLive != null && !financeLive.isEmpty()) {
            response.put("financeLive", financeLive);
        }
        response.put("sources", appendSources(response.get("sources"), "nx_wallet_ledger"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> operationsOverview() {
        return operationsOverview("week", "ALL", null, null);
    }

    public ApiResult<Map<String, Object>> operationsOverview(
            String period, String phase, String from, String to) {
        return ApiResult.ok(reportRepository.operationsDashboard(period, phase, from, to));
    }

    public ApiResult<Map<String, Object>> operationsDevices(
            String period, String phase, String from, String to, String generation, String model) {
        return operationsSection("device", period, phase, from, to, linked(
                "generation", trimOrDefault(generation, "ALL"),
                "model", trimOrDefault(model, "ALL")));
    }

    public ApiResult<Map<String, Object>> operationsTasks(
            String period, String phase, String from, String to, String tier) {
        return operationsSection("tasks", period, phase, from, to,
                linked("tier", trimOrDefault(tier, "ALL")));
    }

    public ApiResult<Map<String, Object>> operationsNetwork(
            String period, String phase, String from, String to) {
        return operationsSection("network", period, phase, from, to, Map.of());
    }

    public ApiResult<Map<String, Object>> operationsPhaseEffect(
            String period, String phase, String from, String to, String metric) {
        return operationsSection("phaseEffect", period, phase, from, to,
                linked("metric", trimOrDefault(metric, "all")));
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> exportNetworkTree(
            String period, String detail, Integer depth, String reason, String idempotencyKey) {
        ApiResult<Map<String, Object>> guard = requireNetworkTreeCommand(idempotencyKey, reason);
        if (guard != null) return guard;
        if (!"tree".equalsIgnoreCase(trimOrDefault(detail, ""))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NETWORK_TREE_DETAIL_REQUIRED");
        }
        String normalizedPeriod = normalizeNetworkTreePeriod(period);
        if (normalizedPeriod == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NETWORK_TREE_PERIOD_INVALID");
        }
        int normalizedDepth = depth == null ? 3 : depth;
        if (normalizedDepth < 1 || normalizedDepth > 10) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NETWORK_TREE_DEPTH_INVALID");
        }
        Long adminId = parseAdminIdFromContext();
        if (adminId == null) return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
        if (!permissionCache.getPermissionCodes(adminId).contains("bi_l4_export_tree")) {
            return ApiResult.fail(403, "PERMISSION_DENIED");
        }
        String hash = sha256(String.join("\u001f", normalizedPeriod, String.valueOf(normalizedDepth),
                reason.trim(), trimOrDefault(currentActorUsername(), "")));
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "L4_NETWORK_TREE_EXPORT", idempotencyKey.trim(), hash, ApiResult.class,
                () -> exportNetworkTreeOnce(normalizedPeriod, normalizedDepth, reason.trim(), idempotencyKey.trim()));
    }

    private ApiResult<Map<String, Object>> exportNetworkTreeOnce(
            String period, int depth, String reason, String idempotencyKey) {
        List<Map<String, Object>> rows = reportRepository.networkTreeRows(period, depth, NETWORK_TREE_ROW_CAP + 1);
        if (rows.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NETWORK_TREE_SCOPE_EMPTY");
        }
        if (rows.size() > NETWORK_TREE_ROW_CAP) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "NETWORK_TREE_ROW_CAP_EXCEEDED");
        }
        String reportId = "L4TREE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String scope = "period=" + period + ";depth=" + depth;
        BiReportView created = reportRepository.createReport(new BiReportCreateCommand(
                reportId,
                "网络/团队结构明细",
                "NETWORK_TREE",
                scope,
                "CSV",
                scope,
                "成员用户编码（部分脱敏）、上级用户编码（部分脱敏）、团队深度、V-Rank、团队GMV、加入时间",
                (long) rows.size(),
                true,
                "PARTIAL",
                "READY",
                "L4-MD1 已确认；24 小时限时下载"));
        reportRepository.saveSnapshotCsv(created.reportId(), networkTreeCsv(rows));
        Map<String, Object> auditDetail = linked(
                "reportId", created.reportId(),
                "reportType", created.type(),
                "scope", created.scope(),
                "fields", created.fields(),
                "rowCount", created.rowCount(),
                "maskingPolicy", created.maskingPolicy(),
                "operator", trimOrDefault(currentActorUsername(), "unknown"),
                "reason", reason,
                "format", created.format(),
                "idempotencyKey", idempotencyKey,
                "alertRecipients", List.of("SUPER_ADMIN", "GROWTH_LEAD"));
        audit("admin.report_exported", created, currentActorUsername(), auditDetail);
        auditResource("admin.sensitive_export_alerted", "BI_EXPORT_ALERT", created.reportId(),
                currentActorUsername(), "HIGH", linked(
                        "reportId", created.reportId(),
                        "recipients", List.of("SUPER_ADMIN", "GROWTH_LEAD"),
                        "delivery", "REAL_TIME_A2_HIGH_RISK_LANE",
                        "reason", reason));
        return ApiResult.ok(linked(
                "reportId", created.reportId(),
                "status", created.status(),
                "rowCount", created.rowCount(),
                "scope", created.scope(),
                "maskingPolicy", created.maskingPolicy(),
                "containsPii", true,
                "downloadTtlHours", 24,
                "downloadTokenPath", "/api/admin/bi/exports/" + created.reportId() + "/download-token",
                "alertedRoles", List.of("SUPER_ADMIN", "GROWTH_LEAD")));
    }

    private ApiResult<Map<String, Object>> operationsSection(
            String section, String period, String phase, String from, String to, Map<String, Object> filters) {
        Map<String, Object> dashboard = reportRepository.operationsDashboard(period, phase, from, to);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("module", "L4");
        response.put("available", dashboard.getOrDefault("available", false));
        response.put("period", dashboard.getOrDefault("period", Map.of()));
        response.put("phaseFilter", dashboard.getOrDefault("phaseFilter", "ALL"));
        response.put("filters", filters);
        response.put(section, dashboard.getOrDefault(section, section.equals("phaseEffect") ? List.of() : Map.of()));
        response.put("quality", dashboard.getOrDefault("quality", Map.of()));
        response.put("sources", dashboard.getOrDefault("sources", List.of()));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> exportOverview() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.dashboard("L5"));
        response.put("module", "L5");
        response.put("summary", reportRepository.overview());
        response.put("ledgerLive", ledgerLiveSummary());
        response.put("reports", reportRepository.reports(null, List.of(), 1, 20));
        response.put("capabilities", linked(
                "aggregateExport", true,
                "networkTreeExport", true,
                "ledgerBillExport", true,
                "download", true,
                "decryptedExport", false,
                "regulatoryReport", true,
                "configMutation", false,
                "scheduleMutation", false,
                "templateMutation", false));
        response.put("exportParams", exportSafetyParams());
        response.put("maskRules", l5MaskRules());
        response.put("crossModuleBlockers", List.of(
                linked("code", "PII_DECRYPTION", "label", "敏感字段解密导出", "status", "BLOCKED",
                        "reason", "当前管理端 API 明确禁止明文敏感字段导出")));
        response.put("sources", List.of(
                "nx_admin_fourth_batch_report", "nx_audit_log", "nx_wallet_ledger",
                "nx_admin_disclosure_jurisdiction", "nx_admin_disclosure_version"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> behaviorHeatmapOverview(String window) {
        String normalizedWindow = normalizeHeatmapWindow(window);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("module", "L6");
        response.put("window", normalizedWindow);
        response.put("available", false);
        response.put("status", "BLOCKED_CROSS_MODULE");
        response.put("message", "行为热力数据源尚未接入，页面不会用业务表行数冒充用户行为事件");
        response.put("requiredEvents", List.of("app.page_viewed", "app.element_clicked"));
        response.put("missingDependencies", List.of(
                linked("module", "APP", "item", "页面浏览埋点 app.page_viewed"),
                linked("module", "APP", "item", "元素点击埋点 app.element_clicked"),
                linked("module", "A4", "item", "埋点目录与字段字典")));
        response.put("totalPages", 0);
        response.put("trackedCount", 0);
        response.put("pageTree", List.of());
        response.put("excludedPages", List.of());
        response.put("activityByWindow", linked("24h", List.of(), "7d", List.of(), "30d", List.of()));
        response.put("activity", List.of());
        response.put("clickHeatByRoute", Map.of());
        response.put("sources", List.of());
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<BiReportView>> reports(BiReportQueryRequest request) {
        int pageNum = normalizePageNum(request == null ? null : request.pageNum());
        int pageSize = normalizePageSize(request == null ? null : request.pageSize(), request == null ? null : request.limit());
        return ApiResult.ok(reportRepository.reports(
                request == null ? null : request.type(),
                normalizeStatuses(request == null ? null : request.status()),
                pageNum,
                pageSize));
    }

    public ApiResult<Map<String, Object>> regulatoryTemplates() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.dashboard("L5"));
        response.put("domain", "L5");
        response.put("templates", response.get("regulatoryTemplates"));
        response.put("exportPolicy", "masked-by-default");
        return ApiResult.ok(response);
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> createReport(String idempotencyKey, BiReportCreateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> inputGuard = validateReportCreateRequest(request);
        if (inputGuard != null) {
            return inputGuard;
        }
        String reportType = normalizeReportType(request.exportType());
        if (!AGGREGATE_EXPORT_TYPES.contains(reportType)) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "L5_EXPORT_TYPE_NOT_AVAILABLE");
        }
        boolean containsPii = containsSensitiveFields(request);
        if (containsPii) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "AGGREGATE_EXPORT_MUST_BE_NON_SENSITIVE");
        }
        Long adminId = parseAdminIdFromContext();
        if (adminId == null) {
            return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
        }
        String requiredPermission = createReportPermission(reportType, false);
        Set<String> grantedPermissions = permissionCache.getPermissionCodes(adminId);
        if (!grantedPermissions.contains(requiredPermission)) {
            return ApiResult.fail(403, "PERMISSION_DENIED");
        }
        if ("FINANCE_AGG".equals(reportType) && containsPii) {
            return ApiResult.fail(
                    OpsErrorCode.RETIRED_FEATURE.httpStatus(),
                    "FINANCE_DETAIL_EXPORT_NOT_AVAILABLE");
        }
        if ("NETWORK_TREE".equals(reportType)) {
            return ApiResult.fail(
                    OpsErrorCode.RETIRED_FEATURE.httpStatus(),
                    "NETWORK_TREE_EXPORT_NOT_AVAILABLE");
        }
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "L_BI_REPORT_CREATE",
                idempotencyKey.trim(),
                reportRequestHash(request),
                ApiResult.class,
                () -> createReportOnce(idempotencyKey.trim(), request));
    }

    private ApiResult<Map<String, Object>> createReportOnce(String idempotencyKey, BiReportCreateRequest request) {
        String reportName = trimOrDefault(request.exportType(), "后台导出报表");
        String maskingPolicy = normalizeMaskingPolicy(request.maskPolicy());
        if ("DECRYPTED".equals(maskingPolicy)) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "DECRYPTED_PII_EXPORT_BLOCKED");
        }
        boolean containsPii = containsSensitiveFields(request);
        String reportId = "EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        BiReportView created = reportRepository.createReport(new BiReportCreateCommand(
                reportId,
                reportName,
                normalizeReportType(reportName),
                trimOrDefault(request.timeRange(), "ON_DEMAND"),
                "REGULATORY".equals(normalizeReportType(reportName)) ? "PDF" : "CSV",
                trimOrDefault(request.timeRange(), "按需导出"),
                trimOrDefault(request.fields(), "聚合指标"),
                estimateRowCount(request),
                containsPii,
                containsPii ? maskingPolicy : "NONE",
                containsPii ? "PENDING_CONFIRM" : "READY",
                StringUtils.hasText(request.ticket()) ? "工单:" + request.ticket().trim() : "后台创建导出任务"));
        reportRepository.saveSnapshotCsv(created.reportId(), reportCsv(created));
        audit(containsPii ? "L_BI_REPORT_CREATE" : "admin.report_exported", created, currentActorUsername(), linked(
                "reportId", created.reportId(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey,
                "recipient", trimOrDefault(request.recipient(), "未指定"),
                "containsPii", created.containsPii(),
                "maskingPolicy", created.maskingPolicy(),
                "scope", created.scope(),
                "fields", created.fields(),
                "rowCount", created.rowCount(),
                "format", created.format(),
                "status", created.status(),
                "snapshot", "CREATION_TIME"));
        Map<String, Object> response = exportOverview().getData();
        response.put("created", created);
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateExportParam(String paramKey, String idempotencyKey, BiDashboardValueRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(paramKey) || !StringUtils.hasText(request.value())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BI_EXPORT_PARAM_VALUE_REQUIRED");
        }
        return ApiResult.fail(501, "BI_DASHBOARD_PAYLOAD_DISABLED");
    }

    public ApiResult<Map<String, Object>> updateRegulatorySchedule(String idempotencyKey, BiDashboardValueRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(request.value())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BI_REGULATORY_SCHEDULE_REQUIRED");
        }
        return ApiResult.fail(501, "BI_DASHBOARD_PAYLOAD_DISABLED");
    }

    public ApiResult<Map<String, Object>> createRegulatoryTemplate(String idempotencyKey, BiRegulatoryTemplateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(request.name())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BI_REGULATORY_TEMPLATE_NAME_REQUIRED");
        }
        return ApiResult.fail(501, "BI_DASHBOARD_PAYLOAD_DISABLED");
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> reportAction(String reportId, String action, String idempotencyKey, BiReportActionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedAction = normalizeAction(action);
        if (normalizedAction == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "UNSUPPORTED_L_REPORT_ACTION");
        }
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "L_BI_REPORT_ACTION_" + normalizedAction,
                idempotencyKey.trim(),
                reportActionRequestHash(reportId, normalizedAction, request),
                ApiResult.class,
                () -> reportActionOnce(reportId, normalizedAction, idempotencyKey.trim(), request));
    }

    private ApiResult<Map<String, Object>> reportActionOnce(String reportId, String normalizedAction,
                                                             String idempotencyKey, BiReportActionRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("L", "report", reportId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        Long adminId = parseAdminIdFromContext();
        if (adminId == null) {
            return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
        }
        if (Boolean.TRUE.equals(request.includeDecrypted())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "DECRYPTED_PII_EXPORT_BLOCKED");
        }
        if (!StringUtils.hasText(reportId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPORT_ID_REQUIRED");
        }
        BiReportView report = reportRepository.findReport(reportId.trim()).orElse(null);
        if (report == null) {
            return ApiResult.fail(404, "BI_REPORT_NOT_FOUND");
        }
        if (!isSupportedAggregateReport(report) && !"REGULATORY".equals(normalizeText(report.type()))) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "L5_EXPORT_TYPE_NOT_AVAILABLE");
        }
        if (Set.of("GENERATE", "RERUN", "APPROVE", "DOWNLOAD").contains(normalizedAction)
                && reportRepository.findSnapshotCsv(report.reportId()).filter(StringUtils::hasText).isEmpty()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "REPORT_SNAPSHOT_NOT_AVAILABLE");
        }
        String requiredCode = "APPROVE".equals(normalizedAction)
                ? "bi_l5_task_approve"
                : reportAccessPermission(report);
        if (!permissionCache.getPermissionCodes(adminId).contains(requiredCode)) {
            return ApiResult.fail(403, "PERMISSION_DENIED");
        }
        String nextStatus = nextStatus(report, normalizedAction);
        if (nextStatus == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!reportRepository.updateActionIfStatus(
                report.reportId(), normalizedAction, normalizeText(report.status()), nextStatus, request.reason().trim())) {
            return ApiResult.fail(409, "BI_REPORT_STATE_CHANGED");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", report.reportId());
        response.put("action", normalizedAction);
        response.put("status", nextStatus);
        response.put("containsPii", report.containsPii());
        response.put("maskingPolicy", report.maskingPolicy());
        if ("DOWNLOAD".equals(normalizedAction)) {
            response.put("downloadTokenIssued", true);
            response.put("downloadTtlHours", 24);
            response.put("downloadPath", "/api/admin/bi/exports/" + report.reportId() + "/download-token");
        }
        audit("L_BI_REPORT_" + normalizedAction, report, currentActorUsername(), Map.of(
                "reportId", report.reportId(),
                "action", normalizedAction,
                "status", nextStatus,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey,
                "includeSensitive", Boolean.TRUE.equals(request.includeSensitive()),
                "includeDecrypted", false));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> downloadToken(String reportId) {
        if (!StringUtils.hasText(reportId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPORT_ID_REQUIRED");
        }
        BiReportView report = reportRepository.findReport(reportId.trim()).orElse(null);
        if (report == null) {
            return ApiResult.fail(404, "BI_REPORT_NOT_FOUND");
        }
        if (!isDownloadableReport(report)) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "L5_EXPORT_TYPE_NOT_AVAILABLE");
        }
        if (!"READY".equals(normalizeText(report.status()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        ApiResult<Map<String, Object>> permissionFailure = requireReportAccess(report);
        if (permissionFailure != null) {
            return permissionFailure;
        }
        if (reportRepository.findSnapshotCsv(report.reportId()).filter(StringUtils::hasText).isEmpty()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "REPORT_SNAPSHOT_NOT_AVAILABLE");
        }
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = issuedAt.plusHours(24);
        String downloadToken = UUID.randomUUID().toString();
        reportRepository.saveDownloadToken(report.reportId(), sha256(downloadToken), expiresAt);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", report.reportId());
        response.put("downloadToken", downloadToken);
        response.put("issuedAt", issuedAt);
        response.put("expiresAt", expiresAt);
        response.put("maskingPolicy", report.maskingPolicy());
        response.put("containsPii", report.containsPii());
        response.put("decryptedPiiBlocked", true);
        response.put("sources", List.of("nx_admin_fourth_batch_report", "nx_audit_log"));
        return ApiResult.ok(response);
    }

    public ApiResult<BiReportDownloadFile> downloadFile(String reportId, String downloadToken) {
        if (!StringUtils.hasText(reportId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPORT_ID_REQUIRED");
        }
        if (!StringUtils.hasText(downloadToken)) {
            return ApiResult.fail(403, "DOWNLOAD_TOKEN_REQUIRED");
        }
        BiReportView report = reportRepository.findReport(reportId.trim()).orElse(null);
        if (report == null) {
            return ApiResult.fail(404, "BI_REPORT_NOT_FOUND");
        }
        if (!isDownloadableReport(report)) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "L5_EXPORT_TYPE_NOT_AVAILABLE");
        }
        if (!"READY".equals(normalizeText(report.status()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        ApiResult<BiReportDownloadFile> permissionFailure = requireReportFileAccess(report);
        if (permissionFailure != null) {
            return permissionFailure;
        }
        Optional<String> snapshot = reportRepository.findSnapshotCsv(report.reportId()).filter(StringUtils::hasText);
        if (snapshot.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "REPORT_SNAPSHOT_NOT_AVAILABLE");
        }
        if (!reportRepository.isDownloadTokenValid(report.reportId(), sha256(downloadToken.trim()), LocalDateTime.now())) {
            return ApiResult.fail(403, "DOWNLOAD_TOKEN_INVALID_OR_EXPIRED");
        }
        String body = "\ufeff" + snapshot.get();
        String fileName = report.reportId().toLowerCase(Locale.ROOT) + ".csv";
        auditResource("L_BI_REPORT_DOWNLOAD_FILE", "BI_REPORT", report.reportId(), currentActorUsername(),
                Boolean.TRUE.equals(report.containsPii()) ? "HIGH" : "MEDIUM", Map.of(
                "reportId", report.reportId(),
                "type", report.type(),
                "ledgerBillCount", ledgerRepository.countLedgerBills(null, null, null),
                "maskingPolicy", report.maskingPolicy(),
                "scope", report.scope(),
                "fields", report.fields(),
                "rowCount", report.rowCount(),
                "format", report.format(),
                "snapshot", snapshot.isPresent() ? "CREATION_TIME" : "LEGACY_LIVE_FALLBACK",
                 "content", "KPI_SERIES".equals(normalizeText(report.type())) ? "L1_KPI_FACTS"
                         : "FUNNEL_COHORT".equals(normalizeText(report.type())) ? "L2_LIFECYCLE_FACTS"
                         : "FINANCE_AGG".equals(normalizeText(report.type())) ? "L3_FINANCE_FACTS"
                         : "OPERATIONS_AGG".equals(normalizeText(report.type())) ? "L4_OPERATIONS_FACTS"
                         : "NETWORK_TREE".equals(normalizeText(report.type())) ? "L4_NETWORK_TREE_PARTIAL" : "REPORT_METADATA"));
        return ApiResult.ok(new BiReportDownloadFile(fileName, "text/csv;charset=UTF-8", body.getBytes(StandardCharsets.UTF_8)));
    }

    private String reportCsv(BiReportView report) {
        return switch (normalizeText(report.type())) {
            case "KPI_SERIES" -> kpiReportCsv();
            case "FUNNEL_COHORT" -> funnelReportCsv();
            case "FINANCE_AGG" -> financeReportCsv();
            case "OPERATIONS_AGG" -> operationsReportCsv(report);
            case "NETWORK_TREE" -> networkTreeReportCsv(report);
            default -> metadataReportCsv(report);
        };
    }

    private String kpiReportCsv() {
        Map<String, Object> dashboard = reportRepository.dashboard("L1");
        Object kpis = dashboard.get("kpis");
        if (kpis instanceof List<?> rows && !rows.isEmpty()) {
            StringBuilder csv = new StringBuilder(csvRow(List.of("指标编号", "指标名称", "当前值", "目标值", "单位", "同期群", "成熟度")));
            for (Object item : rows) {
                Map<String, Object> row = mutableObjectMap(item);
                csv.append(csvRow(List.of(
                        stringValue(row.getOrDefault("n", row.get("kpiId"))),
                        stringValue(row.get("name")),
                        stringValue(row.get("value")),
                        stringValue(row.get("target")),
                        stringValue(row.get("unit")),
                        stringValue(row.get("cohort")),
                        stringValue(row.getOrDefault("vis", row.get("maturity"))))));
            }
            return csv.toString();
        }
        Map<String, Object> totals = mutableObjectMap(dashboard.get("totals"));
        StringBuilder csv = new StringBuilder(csvRow(List.of("指标", "当前值")));
        totals.forEach((metric, value) -> csv.append(csvRow(List.of(kpiFactLabel(metric), stringValue(value)))));
        return csv.toString();
    }

    private String funnelReportCsv() {
        Map<String, Object> dashboard = reportRepository.dashboard("L2");
        Object funnel = dashboard.get("funnel");
        if (funnel instanceof List<?> rows && !rows.isEmpty()) {
            StringBuilder csv = new StringBuilder(csvRow(List.of("生命周期阶段", "去重人数", "转化率", "业务事件", "数据来源")));
            for (Object item : rows) {
                Map<String, Object> row = mutableObjectMap(item);
                csv.append(csvRow(List.of(
                        lifecycleStageLabel(stringValue(row.get("stage"))),
                        stringValue(row.get("users")),
                        stringValue(row.getOrDefault("cvr", row.get("conversionRate"))),
                        stringValue(row.getOrDefault("ev", row.get("event"))),
                        businessSourceLabel(stringValue(row.get("source"))))));
            }
            return csv.toString();
        }
        StringBuilder csv = new StringBuilder(csvRow(List.of("生命周期阶段", "数量", "数据来源")));
        Object stages = dashboard.get("stages");
        if (stages instanceof List<?> rows) {
            for (Object item : rows) {
                Map<String, Object> row = mutableObjectMap(item);
                csv.append(csvRow(List.of(
                        lifecycleStageLabel(stringValue(row.get("key"))),
                        stringValue(row.get("count")),
                        businessSourceLabel(stringValue(row.get("source"))))));
            }
        }
        return csv.toString();
    }

    private String financeReportCsv() {
        StringBuilder csv = new StringBuilder(csvRow(List.of("分类", "财务事实", "当前值", "单位", "数据来源")));
        financeFactRows().forEach(row -> csv.append(csvRow(row)));
        return csv.toString();
    }

    private List<List<String>> financeFactRows() {
        List<List<String>> rows = new ArrayList<>();
        ledgerLiveSummary().forEach((metric, value) -> rows.add(List.of(
                "钱包账单", financeFactLabel(metric), stringValue(value), "笔", "钱包流水")));

        Map<String, Object> finance = mutableObjectMap(financeAnalyticsFacade.currentFinanceSnapshot());
        Map<String, Object> snapshot = mutableObjectMap(finance.get("snapshot"));
        addFinanceSnapshotRow(rows, snapshot, "reserveUsd", "真实储备", "USD");
        addFinanceSnapshotRow(rows, snapshot, "liabilitiesUsd", "应付负债", "USD");
        addFinanceSnapshotRow(rows, snapshot, "coverageRatio", "兑付覆盖率", "%");
        addFinanceSnapshotRow(rows, snapshot, "redlinePct", "覆盖率红线", "%");
        addFinanceSnapshotRow(rows, snapshot, "netFlow24hUsd", "近 24 小时净资金流", "USD");
        addFinanceSnapshotRow(rows, snapshot, "queueBacklogCount", "待处理提现笔数", "笔");
        addFinanceSnapshotRow(rows, snapshot, "queueBacklogUsd", "待处理提现金额", "USD");
        addFinanceSnapshotRow(rows, snapshot, "avgRiskScore", "待处理提现平均风险分", "分");

        Object accounts = finance.get("accounts");
        if (accounts instanceof List<?> values) {
            for (Object value : values) {
                Map<String, Object> account = mutableObjectMap(value);
                if (!account.containsKey("key") || !account.containsKey("amount")) continue;
                rows.add(List.of(
                        "负债科目",
                        liabilityAccountLabel(stringValue(account.get("key"))),
                        stringValue(account.get("amount")),
                        "USD",
                        "负债账本"));
            }
        }

        Object maturity = finance.get("maturity7d");
        if (maturity instanceof List<?> values) {
            for (Object value : values) {
                Map<String, Object> item = mutableObjectMap(value);
                String day = stringValue(item.get("day"));
                if (item.containsKey("withdrawUsd")) {
                    rows.add(List.of("七日到期", day + " 提现到期", stringValue(item.get("withdrawUsd")), "USD", "到期排程"));
                }
                if (item.containsKey("interestUsd")) {
                    rows.add(List.of("七日到期", day + " 利息到期", stringValue(item.get("interestUsd")), "USD", "到期排程"));
                }
            }
        }
        return rows;
    }

    private String operationsReportCsv(BiReportView report) {
        L4ExportSelection selection = l4Selection(report == null ? null : report.cycle());
        StringBuilder csv = new StringBuilder(csvRow(List.of("分类", "周期/阶段", "运营指标", "值", "单位", "数据来源")));
        operationsFactRows(selection).forEach(row -> csv.append(csvRow(row)));
        return csv.toString();
    }

    private String networkTreeReportCsv(BiReportView report) {
        Map<String, String> selection = new LinkedHashMap<>();
        String raw = report == null ? null : report.scope();
        if (raw != null) {
            Arrays.stream(raw.split(";"))
                    .map(part -> part.split("=", 2))
                    .filter(parts -> parts.length == 2)
                    .forEach(parts -> selection.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim()));
        }
        String period = normalizeNetworkTreePeriod(selection.get("period"));
        if (period == null) period = "week";
        int depth;
        try {
            depth = Integer.parseInt(trimOrDefault(selection.get("depth"), "3"));
        } catch (NumberFormatException ignored) {
            depth = 3;
        }
        return networkTreeCsv(reportRepository.networkTreeRows(period, Math.max(1, Math.min(10, depth)), NETWORK_TREE_ROW_CAP));
    }

    private String networkTreeCsv(List<Map<String, Object>> rows) {
        StringBuilder csv = new StringBuilder(csvRow(List.of(
                "成员用户编码（部分脱敏）", "上级用户编码（部分脱敏）", "团队深度", "V-Rank", "团队GMV USDT", "加入时间")));
        for (Map<String, Object> row : rows) {
            csv.append(csvRow(List.of(
                    safeNetworkCsvValue(row.get("memberUserIdPartial")),
                    safeNetworkCsvValue(row.get("sponsorUserIdPartial")),
                    safeNetworkCsvValue(row.get("treeDepth")),
                    safeNetworkCsvValue(row.get("vRank")),
                    safeNetworkCsvValue(row.get("teamVolumeUsdt")),
                    safeNetworkCsvValue(row.get("joinedAt")))));
        }
        return csv.toString();
    }

    private String safeNetworkCsvValue(Object value) {
        String text = stringValue(value);
        return text.matches("^[=+\\-@].*") ? "'" + text : text;
    }

    private List<List<String>> operationsFactRows(L4ExportSelection selection) {
        Map<String, Object> dashboard = reportRepository.operationsDashboard(
                selection.period(), selection.phase(), selection.from(), selection.to());
        String slice = stringValue(mutableObjectMap(dashboard.get("period")).get("label"))
                + " / " + stringValue(dashboard.get("phaseFilter"));
        List<List<String>> rows = new ArrayList<>();
        Map<String, Object> device = mutableObjectMap(dashboard.get("device"));
        Map<String, Object> tasks = mutableObjectMap(dashboard.get("tasks"));
        Map<String, Object> network = mutableObjectMap(dashboard.get("network"));
        appendOperationsSummary(rows, slice, "设备", mutableObjectMap(device.get("summary")), Map.of(
                "activeDevices", "当前活跃设备", "periodPurchasedDevices", "期间设备购置数",
                "periodRetiredDevices", "期间设备退役数", "periodLockedDevices", "期间设备锁定数",
                "periodFirstYieldDevices", "期间完成首次产出设备", "dailyYieldUsdt", "期间产出 USDT",
                "dailyYieldNex", "期间产出 NEX", "degradationLossUsdt", "衰减影响 USDT"), "A4 设备/产出事件");
        appendOperationsSummary(rows, slice, "任务", mutableObjectMap(tasks.get("summary")), Map.of(
                "dispatched", "任务派发量", "completed", "任务完成量", "acceptanceRate", "任务承接率",
                "queueSaturation", "队列饱和度", "checkinActive", "签到活跃人数"), "A4 任务/签到事件");
        appendOperationsSummary(rows, slice, "网络", mutableObjectMap(network.get("summary")), Map.of(
                "directRefs", "直推用户数（被推荐用户去重）", "commissionEvents", "佣金事件数",
                "commissionPaidUsdt", "佣金派发 USDT", "teamGmvUsdt", "团队 GMV USDT",
                "promotionRate", "L4→L5 推广率", "commissionTriggerRate", "团队佣金触发率"), "A4/F 团队事件");
        Object phaseRows = dashboard.get("phaseEffect");
        if (phaseRows instanceof List<?> values) {
            for (Object value : values) {
                Map<String, Object> phase = mutableObjectMap(value);
                String phaseCode = stringValue(phase.get("phase"));
                appendOperationsFact(rows, slice, "Phase", phaseCode + " 活跃人数", phase.get("activeUsers"), "人", "A4/H1 Phase 事件");
                appendOperationsFact(rows, slice, "Phase", phaseCode + " 留存率", phase.get("retentionRate"), "%", "A4/H1 Phase 事件");
                appendOperationsFact(rows, slice, "Phase", phaseCode + " 转化率", phase.get("conversionRate"), "%", "A4/H1 Phase 事件");
                appendOperationsFact(rows, slice, "Phase", phaseCode + " 产出", phase.get("yieldUsdt"), "USDT", "A4/H1 Phase 事件");
            }
        }
        return rows;
    }

    private void appendOperationsSummary(List<List<String>> rows, String slice, String category,
                                         Map<String, Object> summary, Map<String, String> labels, String source) {
        labels.forEach((key, label) -> appendOperationsFact(rows, slice, category, label, summary.get(key),
                key.toLowerCase(Locale.ROOT).contains("rate") || key.toLowerCase(Locale.ROOT).contains("saturation") ? "%"
                        : key.toLowerCase(Locale.ROOT).contains("usdt") ? "USDT"
                        : key.toLowerCase(Locale.ROOT).contains("nex") ? "NEX" : "条",
                source));
    }

    private void appendOperationsFact(List<List<String>> rows, String slice, String category,
                                      String label, Object raw, String unit, String source) {
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) return;
        rows.add(List.of(category, slice, label, stringValue(raw), unit, source));
    }

    private L4ExportSelection l4Selection(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw != null) {
            Arrays.stream(raw.split(";"))
                    .map(part -> part.split("=", 2))
                    .filter(parts -> parts.length == 2)
                    .forEach(parts -> values.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim()));
        }
        return new L4ExportSelection(
                trimOrDefault(values.get("period"), "week"),
                trimOrDefault(values.get("phase"), "ALL"),
                values.get("from"),
                values.get("to"));
    }

    private record L4ExportSelection(String period, String phase, String from, String to) { }

    private void addFinanceSnapshotRow(List<List<String>> rows, Map<String, Object> snapshot,
                                       String key, String label, String unit) {
        if (snapshot.containsKey(key)) {
            rows.add(List.of("资金池概览", label, stringValue(snapshot.get(key)), unit, "资金池总账"));
        }
    }

    private String liabilityAccountLabel(String key) {
        return switch (key) {
            case "balance" -> "可提现余额";
            case "stake_principal" -> "质押本金";
            case "stake_interest" -> "质押应计利息";
            case "nex_payable" -> "NEX 应付款";
            case "withdraw_queue" -> "提现队列";
            case "commission_cooling" -> "佣金冷静期余额";
            case "pending_withdraw" -> "钱包待提现金额";
            default -> "其他负债";
        };
    }

    private String metadataReportCsv(BiReportView report) {
        return csvRow(List.of("report_id", "name", "type", "cycle", "format", "scope", "fields", "row_count", "ledger_bill_count", "contains_pii", "masking_policy", "status"))
                + csvRow(List.of(
                        report.reportId(),
                        report.name(),
                        report.type(),
                        report.cycle(),
                        report.format(),
                        report.scope(),
                        report.fields(),
                        String.valueOf(report.rowCount()),
                        String.valueOf(ledgerRepository.countLedgerBills(null, null, null)),
                        String.valueOf(Boolean.TRUE.equals(report.containsPii())),
                        report.maskingPolicy(),
                        report.status()));
    }

    private String kpiFactLabel(String key) {
        return switch (key) {
            case "users" -> "注册用户";
            case "orders" -> "订单记录";
            case "withdrawals" -> "提现申请";
            case "exchanges" -> "兑换记录";
            case "stakingPositions" -> "质押持仓";
            case "walletLedgerRows" -> "钱包流水";
            case "supportTickets" -> "客服工单";
            case "auditLogs" -> "审计日志";
            default -> "其他业务指标";
        };
    }

    private String financeFactLabel(String key) {
        return switch (key) {
            case "totalBills" -> "钱包账单总数";
            case "earningBills" -> "收益账单";
            case "teamCommissionBills" -> "团队佣金账单";
            case "genesisDividendBills" -> "Genesis 排放账单";
            case "refundBills" -> "退款账单";
            default -> "其他财务事实";
        };
    }

    private String lifecycleStageLabel(String key) {
        return switch (key) {
            case "registered" -> "已注册";
            case "profileCompleted" -> "已完善资料";
            case "kycSubmitted" -> "已提交 KYC";
            case "kycApproved" -> "KYC 已通过";
            case "ordered" -> "订单记录";
            case "walletActivity" -> "钱包活动";
            default -> StringUtils.hasText(key) ? key : "其他阶段";
        };
    }

    private String businessSourceLabel(String source) {
        return switch (source) {
            case "nx_user" -> "用户主数据";
            case "nx_user_profile" -> "用户资料";
            case "nx_kyc_profile" -> "身份认证记录";
            case "nx_order", "nx_order/nx_admin_device_order" -> "订单主数据";
            case "nx_wallet_ledger/nx_wallet_bill" -> "钱包活动记录";
            default -> "业务统计";
        };
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> ledgerLiveSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalBills", ledgerRepository.countLedgerBills(null, null, null));
        summary.put("earningBills", ledgerRepository.countLedgerBills("EARNING", null, null));
        summary.put("teamCommissionBills", ledgerRepository.countLedgerBills("TEAM_COMMISSION", null, null));
        summary.put("genesisDividendBills", ledgerRepository.countLedgerBills("GENESIS_DIVIDEND", null, null));
        summary.put("refundBills", ledgerRepository.countLedgerBills("REFUND", null, null));
        return summary;
    }

    private List<Object> appendSources(Object rawSources, String source) {
        List<Object> sources = new ArrayList<>();
        if (rawSources instanceof List<?> values) {
            sources.addAll(values);
        }
        if (!sources.contains(source)) {
            sources.add(source);
        }
        return sources;
    }

    private String currentActorUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> values) {
            Object username = values.get("username");
            if (username instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        Object principal = authentication.getPrincipal();
        String username = principal == null ? "" : String.valueOf(principal);
        return "anonymousUser".equals(username) ? null : username;
    }

    private String nextStatus(BiReportView report, String action) {
        String current = normalizeText(report.status());
        boolean sensitive = Boolean.TRUE.equals(report.containsPii());
        return switch (action) {
            case "GENERATE", "RERUN" -> sensitive ? "PENDING_CONFIRM" : "READY";
            case "APPROVE" -> Set.of("PENDING_CONFIRM", "PENDING_SPLIT_CONFIRM").contains(current) ? "READY" : null;
            case "DOWNLOAD" -> "READY".equals(current) ? "READY" : null;
            default -> null;
        };
    }

    private List<Map<String, Object>> maskingRules() {
        return List.of(
                mask("phone", "PII", "hash", "decryption blocked in active API"),
                mask("cardToken", "PII", "last4", "strong confirm required for export"),
                mask("billAmount", "funds", "plain", "batch export requires reason"));
    }

    private List<Map<String, Object>> exportSafetyParams() {
        return List.of(
                linked("k", "聚合导出", "v", "仅支持 KPI / 漏斗 / 财务 / 运营聚合快照", "fixed", true,
                        "s", "导出内容来自对应 L1-L4 服务端实时聚合，创建时固化为只读快照"),
                linked("k", "监管报告", "v", "I5 当前法域 × 披露版本", "fixed", true,
                        "s", "只输出七章完整性、确认进度及业务聚合指标，不输出逐用户明细"),
                linked("k", "账单明细", "v", "D4 七类强制脱敏", "fixed", true,
                        "s", "敏感财务明细须确认 8-200 字用途，最多 10 万行并写统一导出审计"),
                linked("k", "单任务行数上限", "v", "100 万行", "fixed", true,
                        "s", "超过上限的明细导出不在当前 L5 接口放行"),
                linked("k", "下载链接有效期", "v", "24 小时", "fixed", true,
                        "s", "令牌仅保存哈希且过期后失效"),
                linked("k", "敏感数据明文导出", "v", "不可关闭的服务端阻断", "fixed", true,
                        "s", "当前管理端不提供解密或原始敏感数据导出路径"));
    }

    private List<Map<String, Object>> l5MaskRules() {
        return List.of(
                linked("f", "手机号", "cat", "隐私信息", "catTone", "bad", "rule", "masked",
                        "ruleNote", "当前聚合快照不包含该字段", "dec", "不允许", "appr", "服务端阻断"),
                linked("f", "卡 Token", "cat", "隐私信息", "catTone", "bad", "rule", "partial",
                        "ruleNote", "仅允许后 4 位策略", "dec", "不允许", "appr", "服务端阻断"),
                linked("f", "地址 / 证件", "cat", "隐私信息", "catTone", "bad", "rule", "masked",
                        "ruleNote", "当前聚合快照不包含该字段", "dec", "不允许", "appr", "服务端阻断"),
                linked("f", "聚合金额", "cat", "资金", "catTone", "warn", "rule", "partial",
                        "ruleNote", "仅输出汇总值，不输出账户明细", "dec", "不适用", "appr", "创建理由留痕"));
    }

    private Map<String, Object> mask(String field, String className, String policy, String note) {
        return Map.of("field", field, "class", className, "policy", policy, "note", note);
    }

    private Map<String, Object> template(String key, String name, String cycle, String fields) {
        return Map.of("key", key, "name", name, "cycle", cycle, "fields", fields);
    }

    private Map<String, Object> currentPhase() {
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        return linked(
                "code", rhythm.currentPhase(),
                "name", phaseName(rhythm.currentPhase()),
                "index", phaseIndex(rhythm.currentPhase()),
                "month", rhythm.currentMonth(),
                "total", rhythm.totalMonths(),
                "phaseProgressPct", rhythm.phaseProgressPct(),
                "sourceDomain", "H1",
                "focus", phaseFocus(rhythm.currentPhase()));
    }

    private List<Map<String, Object>> phases() {
        return List.of(
                phase("P1", "拉新"),
                phase("P2", "激活"),
                phase("P3", "扩张"),
                phase("P4", "深化"),
                phase("P5", "收紧"),
                phase("P6", "软退场"));
    }

    private Map<String, Object> phase(String code, String name) {
        return Map.of("code", code, "name", name);
    }

    private int phaseIndex(String code) {
        return switch (normalizeText(code)) {
            case "P1" -> 0;
            case "P2" -> 1;
            case "P3" -> 2;
            case "P4" -> 3;
            case "P5" -> 4;
            case "P6" -> 5;
            default -> 0;
        };
    }

    private String phaseName(String code) {
        return switch (normalizeText(code)) {
            case "P1" -> "拉新期";
            case "P2" -> "激活期";
            case "P3" -> "扩张期";
            case "P4" -> "深化期";
            case "P5" -> "收紧期";
            case "P6" -> "软退场期";
            default -> "未定义阶段";
        };
    }

    private String phaseFocus(String code) {
        return switch (normalizeText(code)) {
            case "P1" -> "重心:获客、注册和首笔收益体验";
            case "P2" -> "重心:激活、KYC 和首购转化";
            case "P3" -> "重心:拉新 + 首购转化,放宽试用,谨慎放大资金流出";
            case "P4" -> "重心:复投、留存和任务节奏稳定";
            case "P5" -> "重心:收紧奖励、控制提现和佣金流出";
            case "P6" -> "重心:软退场、报表归档和风险敞口收口";
            default -> "重心:等待 H1 节奏配置";
        };
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            response.put((String) pairs[i], pairs[i + 1]);
        }
        return response;
    }

    private String csvRow(List<String> values) {
        return values.stream()
                .map(this::csvCell)
                .reduce((left, right) -> left + "," + right)
                .orElse("")
                + "\n";
    }

    private String csvCell(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (reason.trim().length() < 8 || reason.trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), "REASON_LENGTH_INVALID");
        }
        return null;
    }

    private ApiResult<Map<String, Object>> requireNetworkTreeCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(400, OpsErrorCode.REASON_REQUIRED.name());
        }
        String normalized = reason.trim();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 8 || length > 200) {
            return ApiResult.fail(400, "REASON_LENGTH_INVALID");
        }
        return null;
    }

    private ApiResult<Map<String, Object>> validateReportCreateRequest(BiReportCreateRequest request) {
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BI_REPORT_REQUIRED_FIELD_MISSING");
        }
        if (!StringUtils.hasText(request.exportType())
                || !StringUtils.hasText(request.timeRange())
                || !StringUtils.hasText(request.fields())
                || !StringUtils.hasText(request.piiLevel())
                || !StringUtils.hasText(request.maskPolicy())
                || !StringUtils.hasText(request.recipient())
                || !StringUtils.hasText(request.ticket())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BI_REPORT_REQUIRED_FIELD_MISSING");
        }
        if (exceeds(request.exportType(), 128)
                || exceeds(request.timeRange(), 64)
                || exceeds(request.fields(), 255)
                || exceeds(request.piiLevel(), 64)
                || exceeds(request.maskPolicy(), 64)
                || exceeds(request.recipient(), 128)
                || exceeds(request.ticket(), 220)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BI_REPORT_FIELD_LENGTH_INVALID");
        }
        return null;
    }

    private boolean exceeds(String value, int maxLength) {
        return value != null && value.trim().length() > maxLength;
    }

    private Long parseAdminIdFromContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(authentication.getPrincipal()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeAction(String action) {
        String normalized = normalizeText(action);
        if (!ACTIONS.contains(normalized)) {
            return null;
        }
        return normalized;
    }

    private int normalizePageNum(Integer pageNum) {
        int value = pageNum == null ? 1 : pageNum;
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 10_000);
    }

    private int normalizePageSize(Integer pageSize, Integer legacyLimit) {
        int value = pageSize != null ? pageSize : (legacyLimit == null ? 20 : legacyLimit);
        if (value < 1) {
            return 20;
        }
        return Math.min(value, 100);
    }

    private List<String> normalizeStatuses(String status) {
        if (!StringUtils.hasText(status)) {
            return List.of();
        }
        String normalized = normalizeText(status);
        if ("ALL".equals(normalized)) {
            return List.of();
        }
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String normalizeReportType(String reportName) {
        String text = reportName == null ? "" : reportName.trim();
        String upper = text.toUpperCase(Locale.ROOT);
        if (text.contains("监管") || upper.contains("REGULATORY")) {
            return "REGULATORY";
        }
        if (text.contains("账单") || upper.contains("BILL")) {
            return "BILL_CSV";
        }
        if (text.contains("团队") || upper.contains("NETWORK")) {
            return "NETWORK_TREE";
        }
        if (text.contains("漏斗") || upper.contains("FUNNEL")) {
            return "FUNNEL_COHORT";
        }
        if (text.contains("财务") || upper.contains("FINANCE")) {
            return "FINANCE_AGG";
        }
        if (text.contains("运营") || upper.contains("OPERATIONS")) {
            return "OPERATIONS_AGG";
        }
        if (upper.contains("KPI")) {
            return "KPI_SERIES";
        }
        return "ON_DEMAND";
    }

    private String createReportPermission(String reportType, boolean containsPii) {
        return switch (normalizeText(reportType)) {
            case "KPI_SERIES" -> "bi_l1_write";
            case "FUNNEL_COHORT" -> "bi_l2_write";
            case "FINANCE_AGG" -> containsPii ? "bi_l3_export_detail" : "bi_l3_write";
            case "OPERATIONS_AGG" -> "bi_l4_write";
            case "KYC_REGULATORY" -> "user_c4_export";
            case "REGULATORY" -> "bi_l5_regulatory_generate";
            case "NETWORK_TREE" -> "bi_l4_export_tree";
            default -> "bi_l5_write";
        };
    }

    private String reportAccessPermission(BiReportView report) {
        return createReportPermission(report.type(), Boolean.TRUE.equals(report.containsPii()));
    }

    private boolean isSupportedAggregateReport(BiReportView report) {
        return report != null && AGGREGATE_EXPORT_TYPES.contains(normalizeText(report.type()));
    }

    private boolean isDownloadableReport(BiReportView report) {
        return report != null && DOWNLOADABLE_REPORT_TYPES.contains(normalizeText(report.type()));
    }

    private ApiResult<Map<String, Object>> requireReportAccess(BiReportView report) {
        Long adminId = parseAdminIdFromContext();
        if (adminId == null) return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
        return permissionCache.getPermissionCodes(adminId).contains(reportAccessPermission(report))
                ? null
                : ApiResult.fail(403, "PERMISSION_DENIED");
    }

    private ApiResult<BiReportDownloadFile> requireReportFileAccess(BiReportView report) {
        Long adminId = parseAdminIdFromContext();
        if (adminId == null) return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
        return permissionCache.getPermissionCodes(adminId).contains(reportAccessPermission(report))
                ? null
                : ApiResult.fail(403, "PERMISSION_DENIED");
    }

    private String normalizeMaskingPolicy(String value) {
        String text = value == null ? "" : value.trim();
        String upper = text.toUpperCase(Locale.ROOT);
        if ("解密".equals(text) || text.contains("解密导出") || text.contains("明文") || upper.contains("DECRYPTED") || upper.contains("RAW")) {
            return "DECRYPTED";
        }
        if (text.contains("部分") || text.contains("后 4") || text.contains("后4") || upper.contains("PARTIAL")) {
            return "PARTIAL";
        }
        return "MASKED";
    }

    private boolean containsSensitiveFields(BiReportCreateRequest request) {
        String piiLevel = trimOrDefault(request.piiLevel(), "");
        String normalizedLevel = piiLevel.toUpperCase(Locale.ROOT);
        String compactLevel = normalizedLevel.replaceAll("\\s+", "").replace("_", "").replace("-", "");
        boolean explicitlyNonSensitive = "NONE".equals(compactLevel)
                || "NOPII".equals(compactLevel)
                || piiLevel.contains("无 PII")
                || piiLevel.contains("无PII")
                || piiLevel.contains("不含 PII")
                || piiLevel.contains("不含PII")
                || piiLevel.contains("无敏感");
        if (explicitlyNonSensitive) {
            return containsIdentityField(request.fields());
        }
        if (normalizedLevel.contains("PII") || piiLevel.contains("敏感")) {
            return true;
        }
        return containsIdentityField(piiLevel + " " + request.fields() + " " + request.maskPolicy());
    }

    private boolean containsIdentityField(String raw) {
        String text = trimOrDefault(raw, "").toLowerCase(Locale.ROOT);
        return text.contains("pii")
                || text.contains("userid")
                || text.contains("user_id")
                || text.contains("用户编码")
                || text.contains("用户id")
                || text.contains("用户 id")
                || text.contains("phone")
                || text.contains("card")
                || text.contains("passport")
                || text.contains("email")
                || text.contains("address")
                || text.contains("手机")
                || text.contains("手机号")
                || text.contains("证件")
                || text.contains("身份证")
                || text.contains("护照")
                || text.contains("邮箱")
                || text.contains("卡 token")
                || text.contains("地址");
    }

    private long estimateRowCount(BiReportCreateRequest request) {
        String reportType = normalizeReportType(request.exportType());
        if ("KPI_SERIES".equals(reportType)) {
            return dashboardRowCount("L1", "kpis", "totals");
        }
        if ("FUNNEL_COHORT".equals(reportType)) {
            return dashboardRowCount("L2", "funnel", "stages");
        }
        if ("FINANCE_AGG".equals(reportType)) {
            return financeFactRows().size();
        }
        if ("OPERATIONS_AGG".equals(reportType)) {
            return operationsFactRows(l4Selection(request.timeRange())).size();
        }
        String text = trimOrDefault(request.timeRange(), "") + " " + trimOrDefault(request.fields(), "");
        if (text.contains("全量")) {
            return 1_000_000L;
        }
        if ("REGULATORY".equals(reportType)) {
            return 12_000L;
        }
        if (text.contains("聚合")) {
            return 1_000L;
        }
        return 0L;
    }

    private long dashboardRowCount(String moduleCode, String primaryListKey, String fallbackMapOrListKey) {
        Map<String, Object> dashboard = reportRepository.dashboard(moduleCode);
        Object primary = dashboard.get(primaryListKey);
        if (primary instanceof List<?> rows && !rows.isEmpty()) {
            return rows.size();
        }
        Object fallback = dashboard.get(fallbackMapOrListKey);
        if (fallback instanceof List<?> rows) {
            return rows.size();
        }
        if (fallback instanceof Map<?, ?> values) {
            return values.size();
        }
        return 0L;
    }

    private String trimOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private Map<String, Object> mutableObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNetworkTreePeriod(String period) {
        String value = period == null ? "week" : period.trim().toLowerCase(Locale.ROOT);
        return Set.of("day", "week", "month").contains(value) ? value : null;
    }

    private String normalizeHeatmapWindow(String window) {
        String value = window == null ? "" : window.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "24h", "30d" -> value;
            default -> "7d";
        };
    }

    private void auditResource(String action, String resourceType, String resourceId, String operator, String riskLevel, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(actorUsername(operator))
                .result("SUCCESS")
                .riskLevel(riskLevel)
                .detail(detail)
                .build());
    }

    private void audit(String action, BiReportView report, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("BI_REPORT")
                .resourceId(report.reportId())
                .bizNo(report.reportId())
                .actorType("ADMIN")
                .actorUsername(actorUsername(operator))
                .result("SUCCESS")
                .riskLevel(Boolean.TRUE.equals(report.containsPii()) ? "HIGH" : "MEDIUM")
                .detail(detail)
                .build());
    }

    private String actorUsername(String fallback) {
        String resolved = AdminActorResolver.resolve(fallback);
        return StringUtils.hasText(resolved) ? resolved.trim() : "system";
    }

    private String reportRequestHash(BiReportCreateRequest request) {
        String canonical = String.join("\u001f",
                trimOrDefault(request.reason(), ""),
                trimOrDefault(request.exportType(), ""),
                trimOrDefault(request.timeRange(), ""),
                trimOrDefault(request.fields(), ""),
                trimOrDefault(request.piiLevel(), ""),
                trimOrDefault(request.maskPolicy(), ""),
                trimOrDefault(request.recipient(), ""),
                trimOrDefault(request.ticket(), ""),
                trimOrDefault(currentActorUsername(), ""));
        return sha256(canonical);
    }

    private String reportActionRequestHash(String reportId, String action, BiReportActionRequest request) {
        return sha256(String.join("\u001f",
                trimOrDefault(reportId, ""),
                trimOrDefault(action, ""),
                trimOrDefault(request.reason(), ""),
                String.valueOf(Boolean.TRUE.equals(request.includeSensitive())),
                String.valueOf(Boolean.TRUE.equals(request.includeDecrypted())),
                trimOrDefault(currentActorUsername(), "")));
    }

    private String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    @Override
    public String domain() {
        return "L";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            // reportAction 多态端点:replay 走 APPROVE 分支(状态推进 PENDING_CONFIRM→READY,非资金,不动台账)。
            // includeDecrypted=false 保持退役硬阻断语义;includeSensitive=false 复用脱敏口径。
            case "l5_task_approve" -> {
                BiReportActionRequest req = new BiReportActionRequest(reason, operator, false, false);
                return reportAction(str(p, "reportId"), "APPROVE", idem, req);
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
}
