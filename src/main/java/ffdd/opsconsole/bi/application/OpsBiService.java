package ffdd.opsconsole.bi.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsBiService implements AuditReplayable {
    private static final Set<String> ACTIONS = Set.of("GENERATE", "RERUN", "APPROVE", "DOWNLOAD");

    private final BiReportRepository reportRepository;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final TreasuryLedgerRepository ledgerRepository;
    private final AuditLogService auditLogService;
    private final AdminPermissionCache permissionCache;
    private final AuditObjectLockMapper lockMapper;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.overview());
        response.put("domain", "L");
        response.put("capabilities", List.of("BIReport", "ExportJob", "Funnel", "RegulatoryReport"));
        response.put("maskingRules", maskingRules());
        response.put("securityParams", List.of(
                "sensitive exports require confirm-with-reason and A2 audit",
                "download token TTL is 24 hours",
                "decrypted PII export is blocked in active admin API"));
        response.put("sources", List.of("nx_admin_fourth_batch_report", "nx_audit_log", "H1 growth rhythm facade"));
        response.put("currentPhase", currentPhase());
        response.put("phases", phases());
        response.put("l1", reportRepository.dashboard("L1"));
        response.put("l2", reportRepository.dashboard("L2"));
        response.put("l3", reportRepository.dashboard("L3"));
        response.put("l4", reportRepository.dashboard("L4"));
        response.put("l5", exportOverview().getData());
        response.put("l6", behaviorHeatmapOverview("7d").getData());
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> kpiOverview() {
        return ApiResult.ok(reportRepository.dashboard("L1"));
    }

    public ApiResult<Map<String, Object>> funnelOverview() {
        return ApiResult.ok(reportRepository.dashboard("L2"));
    }

    public ApiResult<Map<String, Object>> financeOverview() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.dashboard("L3"));
        response.put("ledgerLive", ledgerLiveSummary());
        response.put("sources", appendSources(response.get("sources"), "nx_wallet_ledger"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> operationsOverview() {
        return ApiResult.ok(reportRepository.dashboard("L4"));
    }

    public ApiResult<Map<String, Object>> exportOverview() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.dashboard("L5"));
        response.put("summary", reportRepository.overview());
        response.put("ledgerLive", ledgerLiveSummary());
        response.put("reports", reportRepository.reports(null, List.of(), 1, 20));
        response.put("sources", List.of("nx_admin_fourth_batch_report", "nx_audit_log", "nx_wallet_ledger"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> behaviorHeatmapOverview(String window) {
        String normalizedWindow = normalizeHeatmapWindow(window);
        Map<String, Object> dashboard = new LinkedHashMap<>(reportRepository.dashboard("L6"));
        Map<String, Object> activityByWindow = mutableObjectMap(dashboard.get("activityByWindow"));
        Object activity = activityByWindow.getOrDefault(normalizedWindow, List.of());
        dashboard.put("window", normalizedWindow);
        dashboard.put("activity", activity);
        dashboard.put("sources", appendSources(dashboard.get("sources"), "nx_audit_log"));
        return ApiResult.ok(dashboard);
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

    public ApiResult<Map<String, Object>> createReport(String idempotencyKey, BiReportCreateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
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
        audit("L_BI_REPORT_CREATE", created, request.operator(), linked(
                "reportId", created.reportId(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "recipient", trimOrDefault(request.recipient(), "未指定"),
                "containsPii", created.containsPii(),
                "maskingPolicy", created.maskingPolicy()));
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

    public ApiResult<Map<String, Object>> reportAction(String reportId, String action, String idempotencyKey, BiReportActionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("L", "report", reportId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String normalizedAction = normalizeAction(action);
        // service 层二次校验（path variable 多态）:APPROVE 需 bi_l5_task_approve(HIGH),GENERATE/RERUN/DOWNLOAD 需 bi_l5_write
        Long adminId = parseAdminIdFromContext();
        if (adminId == null) {
            return ApiResult.fail(401, "ADMIN_AUTH_REQUIRED");
        }
        String requiredCode = "APPROVE".equals(normalizedAction) ? "bi_l5_task_approve" : "bi_l5_write";
        if (!permissionCache.getPermissionCodes(adminId).contains(requiredCode)) {
            return ApiResult.fail(403, "PERMISSION_DENIED");
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
        String nextStatus = nextStatus(report, normalizedAction);
        if (nextStatus == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        reportRepository.updateAction(report.reportId(), normalizedAction, nextStatus, request.reason().trim());
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
        audit("L_BI_REPORT_" + normalizedAction, report, request.operator(), Map.of(
                "reportId", report.reportId(),
                "action", normalizedAction,
                "status", nextStatus,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
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
        if (!"READY".equals(normalizeText(report.status()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        LocalDateTime issuedAt = LocalDateTime.now();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", report.reportId());
        response.put("downloadToken", UUID.randomUUID().toString());
        response.put("issuedAt", issuedAt);
        response.put("expiresAt", issuedAt.plusHours(24));
        response.put("maskingPolicy", report.maskingPolicy());
        response.put("containsPii", report.containsPii());
        response.put("decryptedPiiBlocked", true);
        response.put("sources", List.of("nx_admin_fourth_batch_report", "nx_audit_log"));
        return ApiResult.ok(response);
    }

    public ApiResult<BiReportDownloadFile> downloadFile(String reportId) {
        if (!StringUtils.hasText(reportId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPORT_ID_REQUIRED");
        }
        BiReportView report = reportRepository.findReport(reportId.trim()).orElse(null);
        if (report == null) {
            return ApiResult.fail(404, "BI_REPORT_NOT_FOUND");
        }
        if (!"READY".equals(normalizeText(report.status()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String body = "\ufeff"
                + csvRow(List.of("report_id", "name", "type", "cycle", "format", "scope", "fields", "row_count", "ledger_bill_count", "contains_pii", "masking_policy", "status"))
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
        String fileName = report.reportId().toLowerCase(Locale.ROOT) + ".csv";
        auditResource("L_BI_REPORT_DOWNLOAD_FILE", "BI_REPORT", report.reportId(), currentActorUsername(),
                Boolean.TRUE.equals(report.containsPii()) ? "HIGH" : "MEDIUM", Map.of(
                "reportId", report.reportId(),
                "type", report.type(),
                "ledgerBillCount", ledgerRepository.countLedgerBills(null, null, null),
                "maskingPolicy", report.maskingPolicy()));
        return ApiResult.ok(new BiReportDownloadFile(fileName, "text/csv;charset=UTF-8", body.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> ledgerLiveSummary() {
        return Map.of(
                "totalBills", ledgerRepository.countLedgerBills(null, null, null),
                "refundBills", ledgerRepository.countLedgerBills("REFUND", null, null),
                "teamCommissionBills", ledgerRepository.countLedgerBills("TEAM_COMMISSION", null, null),
                "genesisDividendBills", ledgerRepository.countLedgerBills("GENESIS_DIVIDEND", null, null),
                "earningBills", ledgerRepository.countLedgerBills("EARNING", null, null));
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
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
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
            throw new IllegalArgumentException("Unsupported L report action");
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
        if (upper.contains("KPI")) {
            return "KPI_SERIES";
        }
        return "ON_DEMAND";
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
        if ("NONE".equals(normalizedLevel) || piiLevel.contains("无敏感")) {
            return containsIdentityField(request.fields());
        }
        if (normalizedLevel.contains("PII") || piiLevel.contains("敏感")) {
            return true;
        }
        return containsIdentityField(request.fields() + " " + request.maskPolicy());
    }

    private boolean containsIdentityField(String raw) {
        String text = trimOrDefault(raw, "").toLowerCase(Locale.ROOT);
        return text.contains("pii")
                || text.contains("phone")
                || text.contains("card")
                || text.contains("passport")
                || text.contains("email")
                || text.contains("address")
                || text.contains("手机号")
                || text.contains("证件")
                || text.contains("身份证")
                || text.contains("护照")
                || text.contains("邮箱")
                || text.contains("卡 token")
                || text.contains("地址");
    }

    private long estimateRowCount(BiReportCreateRequest request) {
        String text = trimOrDefault(request.timeRange(), "") + " " + trimOrDefault(request.fields(), "");
        if (text.contains("全量")) {
            return 1_000_000L;
        }
        if (normalizeReportType(request.exportType()).equals("REGULATORY")) {
            return 12_000L;
        }
        if (text.contains("聚合")) {
            return 1_000L;
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

    private String normalizeHeatmapWindow(String window) {
        String value = window == null ? "" : window.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "24h", "30d" -> value;
            default -> "7d";
        };
    }

    private void auditResource(String action, String resourceType, String resourceId, String operator, String riskLevel, Map<String, Object> detail) {
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

    private void audit(String action, BiReportView report, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("BI_REPORT")
                .resourceId(report.reportId())
                .bizNo(report.reportId())
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel(Boolean.TRUE.equals(report.containsPii()) ? "HIGH" : "MEDIUM")
                .detail(detail)
                .build());
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
