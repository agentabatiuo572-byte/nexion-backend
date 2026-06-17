package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsBiService {
    private static final Set<String> ACTIONS = Set.of("GENERATE", "RERUN", "APPROVE", "DOWNLOAD");

    private final BiReportRepository reportRepository;
    private final AuditLogService auditLogService;

    public OpsBiService(BiReportRepository reportRepository, AuditLogService auditLogService) {
        this.reportRepository = reportRepository;
        this.auditLogService = auditLogService;
    }

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.overview());
        response.put("domain", "L");
        response.put("capabilities", List.of("BIReport", "ExportJob", "Funnel", "RegulatoryReport"));
        response.put("maskingRules", maskingRules());
        response.put("securityParams", List.of(
                "sensitive exports require confirm-with-reason and A2 audit",
                "download token TTL is 24 hours",
                "decrypted PII export is blocked in active admin API"));
        response.put("sources", List.of("nx_admin_fourth_batch_report", "nx_audit_log"));
        return ApiResult.ok(response);
    }

    public ApiResult<List<BiReportView>> reports(BiReportQueryRequest request) {
        int limit = normalizeLimit(request == null ? null : request.limit(), 50, 200);
        return ApiResult.ok(reportRepository.reports(
                request == null ? null : request.type(),
                request == null ? null : request.status(),
                limit));
    }

    public ApiResult<Map<String, Object>> regulatoryTemplates() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "L5");
        response.put("templates", List.of(
                template("REG-KYC-Q", "KYC AML audit package", "Quarterly", "KYC status, EDD, sanctions, rejection reasons"),
                template("REG-REDEMPTION-M", "Redemption report", "Monthly", "withdrawal rate, coverage, exposure, maturity"),
                template("REG-FRAUD-M", "Fraud abuse report", "Monthly", "device fingerprint, account graph, blocked withdrawals"),
                template("REG-GEO-Q", "Cross border data list", "Quarterly", "jurisdiction, field class, masking, download audit")));
        response.put("exportPolicy", "masked-by-default");
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> reportAction(String reportId, String action, String idempotencyKey, BiReportActionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedAction = normalizeAction(action);
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

    private String nextStatus(BiReportView report, String action) {
        String current = normalizeText(report.status());
        boolean sensitive = Boolean.TRUE.equals(report.containsPii());
        return switch (action) {
            case "GENERATE", "RERUN" -> sensitive ? "PENDING_CONFIRM" : "GENERATING";
            case "APPROVE" -> Set.of("PENDING_CONFIRM", "PENDING_SPLIT_CONFIRM").contains(current) ? "GENERATING" : null;
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

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private String normalizeAction(String action) {
        String normalized = normalizeText(action);
        if (!ACTIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported L report action");
        }
        return normalized;
    }

    private int normalizeLimit(Integer limit, int fallback, int max) {
        int value = limit == null ? fallback : limit;
        if (value < 1) {
            return fallback;
        }
        return Math.min(value, max);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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
}
