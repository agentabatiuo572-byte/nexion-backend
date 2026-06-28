package ffdd.opsconsole.platform.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.application.OpsAuditCenterService;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditExportRequest;
import ffdd.opsconsole.platform.dto.AuditMechanismParamUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/audit")
@PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
@RequiredArgsConstructor
public class OpsAuditController {
    private final AuditLogService auditLogService;
    private final OpsAuditCenterService auditCenterService;

    @GetMapping("/overview")
    public ApiResult<AuditCenterOverview> overview() {
        return auditCenterService.overview();
    }

    @GetMapping("/logs")
    public ApiResult<List<AuditLogRecord>> logs(AuditLogQueryRequest request) {
        return ApiResult.ok(auditLogService.list(request));
    }

    @GetMapping("/logs/trace/{traceId}")
    public ApiResult<List<AuditLogRecord>> byTrace(@PathVariable String traceId) {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setTraceId(traceId);
        request.setLimit(200);
        return ApiResult.ok(auditLogService.list(request));
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('PERM_AUDIT_EXPORT')")
    public ResponseEntity<?> export(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AuditExportRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return jsonFailure(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            return jsonFailure(OpsErrorCode.REASON_REQUIRED);
        }

        String normalizedKey = idempotencyKey.trim();
        LocalDateTime createdAt = LocalDateTime.now();
        String jobNo = "A2-AUD-EXP-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(createdAt);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("A2_AUDIT_EXPORTED")
                .resourceType("A2_AUDIT_EXPORT")
                .resourceId(jobNo)
                .bizNo(jobNo)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "jobNo", jobNo,
                        "filter", request.filter(),
                        "reason", request.reason().trim(),
                        "idempotencyKey", normalizedKey))
                .build());
        byte[] body = auditExportWorkbook(jobNo, normalizedKey, createdAt, request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.ms-excel;charset=UTF-8"))
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(jobNo + ".xls", StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    private ResponseEntity<ApiResult<Map<String, Object>>> jsonFailure(OpsErrorCode errorCode) {
        return ResponseEntity.status(errorCode.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResult.fail(errorCode.httpStatus(), errorCode.name()));
    }

    private byte[] auditExportWorkbook(
            String jobNo,
            String idempotencyKey,
            LocalDateTime createdAt,
            AuditExportRequest request) {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setLimit(500);
        if (request.filter() != null) {
            applyAuditFilter(query, request.filter());
        }
        List<AuditLogRecord> rows = auditLogService.list(query);
        StringBuilder html = new StringBuilder(8192);
        html.append('\ufeff')
                .append("<html><head><meta charset=\"UTF-8\"></head><body>")
                .append("<table border=\"1\">")
                .append("<tr><th colspan=\"8\">A2 审计日志导出</th></tr>")
                .append("<tr><td>导出单号</td><td colspan=\"7\">").append(escape(jobNo)).append("</td></tr>")
                .append("<tr><td>导出时间</td><td colspan=\"7\">").append(escape(createdAt.toString())).append("</td></tr>")
                .append("<tr><td>幂等键</td><td colspan=\"7\">").append(escape(idempotencyKey)).append("</td></tr>")
                .append("<tr><td>导出原因</td><td colspan=\"7\">").append(escape(request.reason().trim())).append("</td></tr>")
                .append("<tr><th>时间</th><th>操作者</th><th>动作</th><th>资源类型</th><th>资源编号</th>")
                .append("<th>业务编号</th><th>结果</th><th>风险级别</th></tr>");
        for (AuditLogRecord row : rows == null ? List.<AuditLogRecord>of() : rows) {
            html.append("<tr>")
                    .append("<td>").append(escape(row.getCreatedAt() == null ? "" : row.getCreatedAt().toString())).append("</td>")
                    .append("<td>").append(escape(row.getActorUsername())).append("</td>")
                    .append("<td>").append(escape(row.getAction())).append("</td>")
                    .append("<td>").append(escape(row.getResourceType())).append("</td>")
                    .append("<td>").append(escape(row.getResourceId())).append("</td>")
                    .append("<td>").append(escape(row.getBizNo())).append("</td>")
                    .append("<td>").append(escape(row.getResult())).append("</td>")
                    .append("<td>").append(escape(row.getRiskLevel())).append("</td>")
                    .append("</tr>");
        }
        html.append("</table></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void applyAuditFilter(AuditLogQueryRequest query, Map<String, Object> filter) {
        Object action = filter.get("action");
        if (action instanceof String value && StringUtils.hasText(value)) {
            query.setAction(value.trim());
        }
        Object resourceType = filter.get("resourceType");
        if (resourceType instanceof String value && StringUtils.hasText(value)) {
            query.setResourceType(value.trim());
        }
        Object result = filter.get("result");
        if (result instanceof String value && StringUtils.hasText(value)) {
            query.setResult(value.trim());
        }
        Object riskLevel = filter.get("riskLevel");
        if (riskLevel instanceof String value && StringUtils.hasText(value)) {
            query.setRiskLevel(value.trim());
        }
    }

    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @PostMapping("/operations/{operationId}/approve")
    @PreAuthorize("hasAuthority('PERM_AUDIT_EXPORT')")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> approveOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String operationId,
            @RequestBody(required = false) AuditOperationDecisionRequest request) {
        return auditCenterService.approve(idempotencyKey, operationId, request);
    }

    @PostMapping("/operations")
    @PreAuthorize("hasAuthority('PERM_AUDIT_EXPORT')")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> createOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AuditOperationProposalRequest request) {
        return auditCenterService.createProposal(idempotencyKey, request);
    }

    @PostMapping("/operations/{operationId}/reject")
    @PreAuthorize("hasAuthority('PERM_AUDIT_EXPORT')")
    public ApiResult<AuditCenterOverview.AuditOperationTicket> rejectOperation(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String operationId,
            @RequestBody(required = false) AuditOperationDecisionRequest request) {
        return auditCenterService.reject(idempotencyKey, operationId, request);
    }

    @PostMapping("/mechanism-params/{paramKey}")
    @PreAuthorize("hasAuthority('PERM_AUDIT_EXPORT')")
    public ApiResult<AuditCenterOverview.AuditMechanismParam> updateMechanismParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody(required = false) AuditMechanismParamUpdateRequest request) {
        return auditCenterService.updateMechanismParam(idempotencyKey, paramKey, request);
    }

    @GetMapping("/stats/summary")
    public ApiResult<AuditStatsSummaryResponse> summary(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.summary(request));
    }

    @GetMapping("/stats/actions")
    public ApiResult<List<AuditStatsBucket>> actions(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topActions(request));
    }

    @GetMapping("/stats/services")
    public ApiResult<List<AuditStatsBucket>> services(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topServices(request));
    }

    @GetMapping("/stats/users")
    public ApiResult<List<AuditStatsBucket>> users(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topUsers(request));
    }
}
