package ffdd.opsconsole.bi.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsBiExportQueryService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BiReportRepository reportRepository;
    private final AuditLogService auditLogService;

    public ApiResult<BiReportView> exportTask(String exportId) {
        if (!StringUtils.hasText(exportId)) return ApiResult.fail(400, "REPORT_ID_REQUIRED");
        BiReportView report = reportRepository.findReport(exportId.trim()).orElse(null);
        return report == null ? ApiResult.fail(404, "BI_REPORT_NOT_FOUND") : ApiResult.ok(report);
    }

    public ApiResult<List<Map<String, Object>>> exportAudits(
            String operator,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer limit) {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setAction("ADMIN.REPORT_EXPORTED");
        query.setOperator(operator);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        query.setLimit(limit == null ? 50 : Math.max(1, Math.min(limit, 100)));
        return ApiResult.ok(auditLogService.list(query).stream().map(this::auditRow).toList());
    }

    private Map<String, Object> auditRow(AuditLogRecord audit) {
        JsonNode detail = detail(audit.getDetailJson());
        String exportType = text(detail, "exportType");
        BiReportView report = reportRepository.findReport(audit.getResourceId()).orElse(null);
        if (!StringUtils.hasText(exportType) && report != null) exportType = report.type();
        String scope = text(detail, "scope");
        if (!StringUtils.hasText(scope) && report != null) scope = report.scope();
        String jurisdiction = text(detail, "jurisdictionCode");
        String disclosureVersion = text(detail, "disclosureVersion");
        String context = StringUtils.hasText(jurisdiction)
                ? " · " + jurisdiction + "/" + disclosureVersion
                : "";
        String maskingPolicy = text(detail, "maskingPolicy");
        if (!StringUtils.hasText(maskingPolicy) && report != null) maskingPolicy = report.maskingPolicy();
        boolean containsPii = bool(detail, "containsPii", report != null && Boolean.TRUE.equals(report.containsPii()));
        long rowCount = number(detail, "rowCount", report == null || report.rowCount() == null ? 0L : report.rowCount());
        return linked(
                "ts", audit.getCreatedAt() == null ? "—" : audit.getCreatedAt().toString(),
                "who", StringUtils.hasText(audit.getActorUsername()) ? audit.getActorUsername() : "system",
                "what", label(exportType) + " / " + (StringUtils.hasText(scope) ? scope : "按需范围") + context,
                "rows", rowCount,
                "pii", containsPii,
                "mask", "MASKED".equalsIgnoreCase(maskingPolicy) ? "masked"
                        : "PARTIAL".equalsIgnoreCase(maskingPolicy) ? "partial" : "—",
                "chain", StringUtils.hasText(jurisdiction)
                        ? "服务端权限 → 理由确认 → I5 当前披露校验 → 强制审计"
                        : "服务端权限 → 理由确认 → 强制审计",
                "dl", "已记录");
    }

    private JsonNode detail(String raw) {
        if (!StringUtils.hasText(raw)) return JSON.createObjectNode();
        try {
            return JSON.readTree(raw);
        } catch (Exception ignored) {
            return JSON.createObjectNode();
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private boolean bool(JsonNode node, String key, boolean fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private long number(JsonNode node, String key, long fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asLong(fallback);
    }

    private String label(String reportType) {
        return switch (reportType == null ? "" : reportType.trim().toUpperCase(Locale.ROOT)) {
            case "KPI_SERIES" -> "KPI 聚合";
            case "FUNNEL_COHORT" -> "漏斗聚合";
            case "FINANCE_AGG" -> "财务聚合";
            case "OPERATIONS_AGG" -> "运营聚合";
            case "BILL_CSV" -> "七类账单明细";
            case "KYC_REGULATORY" -> "KYC 监管台账";
            case "REGULATORY" -> "监管报告";
            default -> "导出任务";
        };
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return result;
    }
}
